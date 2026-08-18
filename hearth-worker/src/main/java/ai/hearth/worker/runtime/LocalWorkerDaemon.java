package ai.hearth.worker.runtime;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public final class LocalWorkerDaemon implements AutoCloseable {
    private final LocalWorkerClient client;

    public LocalWorkerDaemon(Path executable, Path workingDirectory, String gatewayUrl, String capability) {
        this.client = new LocalWorkerClient(Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8"), gatewayUrl, capability);
        this.executable = executable;
        this.workingDirectory = workingDirectory;
    }

    private final Path executable;
    private final Path workingDirectory;

    public WorkerCommandResult launch(UUID sessionId, long generation, UUID launchId, UUID commandId) {
        return client.launch(new LaunchCommand(commandId,
                new ProcessIdentity(sessionId, generation, launchId), executable, workingDirectory));
    }

    public WorkerCommandResult invoke(UUID sessionId, long generation, UUID launchId, UUID invocationId, UUID commandId, String content) {
        return client.invoke(new InvokeCommand(commandId, invocationId,
                new ProcessIdentity(sessionId, generation, launchId), content));
    }

    public WorkerCommandResult cancel(UUID sessionId, long generation, UUID launchId, UUID commandId) {
        return client.cancel(new CancelCommand(commandId,
                new ProcessIdentity(sessionId, generation, launchId)));
    }

    public java.util.concurrent.Flow.Publisher<WorkerEvent> events(UUID sessionId) {
        return client.events(sessionId);
    }

    @Override
    public void close() {
        client.close();
    }
}
