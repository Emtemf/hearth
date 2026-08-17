package ai.hearth.worker.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StreamJsonFixtureProbeTest {

    @Test
    void givenFixture_whenTwoInputsFollowResult_thenProbeConfirmsStreamingStdin() throws Exception {
        var fixture = Path.of("src/test/resources/fake-claude-stream-json.sh").toAbsolutePath();
        var probe = new StreamJsonFixtureProbe(fixture, Duration.ofSeconds(5));

        var result = probe.run();

        assertThat(result.reusePolicy()).isEqualTo(ProcessReusePolicy.STREAMING_STDIN);
        assertThat(result.eventTypes()).containsExactly(
                "system", "assistant", "result", "assistant", "result");
        assertThat(result.exitCode()).isZero();
    }
}
