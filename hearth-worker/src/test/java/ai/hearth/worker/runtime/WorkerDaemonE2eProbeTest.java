package ai.hearth.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerDaemonE2eProbeTest {
    @Test
    void givenTwoInputFixture_whenProbeRuns_thenVerifiesTwoResultEvents() throws Exception {
        Path script = Files.createTempFile("hearth-e2e-probe", ".sh");
        Files.writeString(script, "#!/bin/sh\nread first\nprintf '%s\\n' '{\"type\":\"result\"}'\nread second\nprintf '%s\\n' '{\"type\":\"result\"}'\nsleep 30\n");
        script.toFile().setExecutable(true);
        try {
            var result = WorkerDaemonE2eProbe.run(script, script.getParent(), "http://127.0.0.1:4517", "capability");
            assertThat(result.passed()).as(result.detail() + " / events=" + result.resultEvents()).isTrue();
            assertThat(result.resultEvents()).isEqualTo(2);
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
