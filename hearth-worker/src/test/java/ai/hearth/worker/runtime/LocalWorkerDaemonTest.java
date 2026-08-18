package ai.hearth.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalWorkerDaemonTest {
    @Test
    void givenFixtureDaemon_whenLaunchInvokeCancel_thenUsesWorkerBoundary() throws Exception {
        Path script = Files.createTempFile("hearth-daemon", ".sh");
        Files.writeString(script, "#!/bin/sh\nread first\nprintf '%s\\n' '{\"type\":\"result\"}'\nsleep 30\n");
        script.toFile().setExecutable(true);
        var sessionId = UUID.randomUUID();
        var launchId = UUID.randomUUID();
        try (var daemon = new LocalWorkerDaemon(script, script.getParent(), "http://127.0.0.1:4517", "capability")) {
            assertThat(daemon.launch(sessionId, 1, launchId, UUID.randomUUID()).state()).isEqualTo("COMMITTED");
            assertThat(daemon.invoke(sessionId, 1, launchId, UUID.randomUUID(), UUID.randomUUID(), "hello").state()).isEqualTo("COMMITTED");
            assertThat(daemon.cancel(sessionId, 1, launchId, UUID.randomUUID()).state()).isEqualTo("COMMITTED");
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
