package ai.hearth.worker.runtime;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public final class WorkerDaemonE2eProbe {
    private WorkerDaemonE2eProbe() {}

    public static ProbeResult run(Path executable, Path workingDirectory, String gatewayUrl, String capability) {
        var sessionId = UUID.randomUUID();
        var launchId = UUID.randomUUID();
        var invocationOne = UUID.randomUUID();
        var invocationTwo = UUID.randomUUID();
        try (var daemon = new LocalWorkerDaemon(executable, workingDirectory, gatewayUrl, capability)) {
            var launch = daemon.launch(sessionId, 1, launchId, UUID.randomUUID());
            if (!"COMMITTED".equals(launch.state())) return new ProbeResult(false, "launch_failed", 0);
            var first = daemon.invoke(sessionId, 1, launchId, invocationOne, UUID.randomUUID(), "first");
            var second = daemon.invoke(sessionId, 1, launchId, invocationTwo, UUID.randomUUID(), "second");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            long results;
            do {
                results = daemon.eventHistory(sessionId).stream().filter(event -> "result".equals(event.type())).count();
                if (results >= 2 || System.nanoTime() >= deadline) break;
                try { Thread.sleep(25); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); break; }
            } while (true);
            var cancel = daemon.cancel(sessionId, 1, launchId, UUID.randomUUID());
            return new ProbeResult("COMMITTED".equals(first.state()) && "COMMITTED".equals(second.state())
                    && "COMMITTED".equals(cancel.state()) && results >= 2, "two_invocations_verified", results);
        }
    }
}

record ProbeResult(boolean passed, String detail, long resultEvents) {}
