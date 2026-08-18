package ai.hearth.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalWorkerClientTest {

    @Test
    void givenFixture_whenLaunchedAndInvokedTwice_thenReusesExactProcessIdentity() throws Exception {
        Path script = Files.createTempFile("hearth-worker-runtime", ".sh");
        Files.writeString(script, "#!/bin/sh\nread first\nprintf '%s\\n' '{\"type\":\"result\"}'\nread second\nprintf '%s\\n' '{\"type\":\"result\"}'\nsleep 30\n", StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        var identity = new ProcessIdentity(UUID.randomUUID(), 1L, UUID.randomUUID());
        var client = new LocalWorkerClient(
                Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8"),
                "http://127.0.0.1:4517/s/session/anthropic",
                "capability");
        try {
            var launch = client.launch(new LaunchCommand(UUID.randomUUID(), identity, script, script.getParent()));
            assertThat(launch.state()).isEqualTo("COMMITTED");

            var first = client.invoke(new InvokeCommand(UUID.randomUUID(), UUID.randomUUID(), identity, "first"));
            var second = client.invoke(new InvokeCommand(UUID.randomUUID(), UUID.randomUUID(), identity, "second"));

            assertThat(first.state()).isEqualTo("COMMITTED");
            assertThat(second.state()).isEqualTo("COMMITTED");
            assertThat(client.cancel(new CancelCommand(UUID.randomUUID(), identity)).state()).isEqualTo("COMMITTED");
        } finally {
            client.close();
            Files.deleteIfExists(script);
        }
    }

    @Test
    void givenWrongGeneration_whenInvoked_thenRejectsBeforeCommit() throws Exception {
        var client = new LocalWorkerClient(Map.of("PATH", "/usr/bin"), "http://127.0.0.1:4517", "capability");
        var identity = new ProcessIdentity(UUID.randomUUID(), 2L, UUID.randomUUID());
        var result = client.invoke(new InvokeCommand(UUID.randomUUID(), UUID.randomUUID(), identity, "ignored"));
        assertThat(result.state()).isEqualTo("REJECTED_BEFORE_COMMIT");
    }
}
