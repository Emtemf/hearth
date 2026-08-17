package ai.hearth.worker.spike;

import java.util.List;

public record ClaudeCodeCommand(List<String> argv) {
    public static ClaudeCodeCommand forSession(java.nio.file.Path executable, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("worker.session_id.required");
        }
        return new ClaudeCodeCommand(List.of(
                executable.toString(),
                "-p",
                "--input-format",
                "stream-json",
                "--output-format",
                "stream-json",
                "--verbose",
                "--replay-user-messages",
                "--session-id",
                sessionId));
    }
}
