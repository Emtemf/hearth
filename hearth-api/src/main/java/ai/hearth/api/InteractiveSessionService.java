package ai.hearth.api;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class InteractiveSessionService {
    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom random = new SecureRandom();
    private final String defaultUpstreamBaseUrl;
    private final String defaultModel;

    InteractiveSessionService(
            JdbcTemplate jdbcTemplate,
            @Value("${hearth.gateway.anthropic.upstream-base-url:http://127.0.0.1:4599}") String defaultUpstreamBaseUrl,
            @Value("${hearth.gateway.default-model:claude-opus-5}") String defaultModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.defaultUpstreamBaseUrl = defaultUpstreamBaseUrl;
        this.defaultModel = defaultModel;
    }

    InteractiveSession create(CreateSessionRequest request) {
        var sessionId = UUID.randomUUID();
        var capability = randomToken();
        jdbcTemplate.update("""
                INSERT INTO hearth_session
                    (id, agent_role, status, observability_level, effective_model,
                     latest_system_prompt, recording_status)
                VALUES (?, ?, 'READY', 'full', ?, ?, 'complete')
                """, sessionId, valueOrDefault(request.agentRole(), "coder"),
                valueOrDefault(request.model(), defaultModel),
                valueOrDefault(request.systemPrompt(), "You are the Hearth coding agent."));
        return new InteractiveSession(sessionId, capability, defaultUpstreamBaseUrl, valueOrDefault(request.model(), defaultModel));
    }

    void appendUserTurn(UUID sessionId, String content) {
        appendTurn(sessionId, "user", content);
    }

    void appendAssistantTurn(UUID sessionId, String content) {
        appendTurn(sessionId, "assistant", content);
    }

    private void appendTurn(UUID sessionId, String role, String content) {
        Integer ordinal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM hearth_transcript_turn WHERE session_id = ?",
                Integer.class,
                sessionId);
        jdbcTemplate.update("""
                INSERT INTO hearth_transcript_turn(session_id, ordinal, role, content, created_at)
                VALUES (?, ?, ?, ?, now())
                """, sessionId, ordinal, role, content);
    }

    private String randomToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
