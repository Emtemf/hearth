package ai.hearth.worker.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClaudeCodeCommandTest {

    @Test
    void givenClaudeExecutableAndSessionId_whenBuilt_thenUsesStreamJsonStdioContract() {
        var command = ClaudeCodeCommand.forSession(Path.of("/usr/local/bin/claude"), "session-id");

        assertThat(command.argv()).containsExactly(
                "/usr/local/bin/claude",
                "-p",
                "--input-format",
                "stream-json",
                "--output-format",
                "stream-json",
                "--verbose",
                "--replay-user-messages",
                "--session-id",
                "session-id");
    }
}
