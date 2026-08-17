package ai.hearth.worker.runtime;

import ai.hearth.worker.spike.ChildEnvironment;
import ai.hearth.worker.spike.ClaudeCodeCommand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class LocalWorkerClient implements WorkerClient, AutoCloseable {
    private final Map<UUID, ManagedProcess> processes = new ConcurrentHashMap<>();
    private final Map<String, String> allowedEnvironment;
    private final String gatewayBaseUrl;
    private final String gatewayCapability;

    public LocalWorkerClient(Map<String, String> allowedEnvironment, String gatewayBaseUrl, String gatewayCapability) {
        this.allowedEnvironment = Map.copyOf(allowedEnvironment);
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.gatewayCapability = gatewayCapability;
    }

    @Override
    public WorkerCommandResult launch(LaunchCommand command) {
        if (processes.containsKey(command.process().sessionId())) {
            return result(command.commandId(), command.process(), "REJECTED_BEFORE_COMMIT", "worker.process_already_exists");
        }
        try {
            var argv = ClaudeCodeCommand.forSession(command.executable(), command.process().sessionId().toString()).argv();
            var environment = new ChildEnvironment(System.getenv(), allowedEnvironment)
                    .forSession(gatewayCapability, gatewayBaseUrl);
            var processBuilder = new ProcessBuilder(argv)
                    .directory(command.workingDirectory().toFile())
                    .redirectErrorStream(false);
            processBuilder.environment().clear();
            processBuilder.environment().putAll(environment);
            var process = processBuilder.start();
            processes.put(command.process().sessionId(), new ManagedProcess(process, command.process()));
            return result(command.commandId(), command.process(), "COMMITTED", "worker.process_started");
        } catch (IOException | RuntimeException exception) {
            return result(command.commandId(), command.process(), "REJECTED_BEFORE_COMMIT", "worker.launch_failed");
        }
    }

    @Override
    public WorkerCommandResult invoke(InvokeCommand command) {
        var managed = processes.get(command.process().sessionId());
        if (managed == null || !managed.identity().equals(command.process())) {
            return result(command.commandId(), command.process(), "REJECTED_BEFORE_COMMIT", "worker.process_identity_mismatch");
        }
        try {
            managed.process().getOutputStream().write((command.content() + "\n").getBytes(StandardCharsets.UTF_8));
            managed.process().getOutputStream().flush();
            return result(command.commandId(), command.process(), "COMMITTED", "worker.invocation_sent");
        } catch (IOException exception) {
            return result(command.commandId(), command.process(), "UNKNOWN_COMMIT_STATE", "worker.invocation_transport_failed");
        }
    }

    @Override
    public WorkerCommandResult cancel(CancelCommand command) {
        var managed = processes.get(command.process().sessionId());
        if (managed == null || !managed.identity().equals(command.process())) {
            return result(command.commandId(), command.process(), "REJECTED_BEFORE_COMMIT", "worker.process_identity_mismatch");
        }
        managed.process().destroy();
        try {
            if (!managed.process().waitFor(8, TimeUnit.SECONDS)) {
                managed.process().destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result(command.commandId(), command.process(), "UNKNOWN_COMMIT_STATE", "worker.cancel_interrupted");
        } finally {
            processes.remove(command.process().sessionId(), managed);
        }
        return result(command.commandId(), command.process(), "COMMITTED", "worker.process_cancelled");
    }

    @Override
    public void close() {
        processes.values().forEach(managed -> managed.process().destroyForcibly());
        processes.clear();
    }

    private WorkerCommandResult result(UUID commandId, ProcessIdentity identity, String state, String detail) {
        return new WorkerCommandResult(commandId, state, identity, detail);
    }

    private record ManagedProcess(Process process, ProcessIdentity identity) {}
}
