package ai.hearth.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
class JdbcSessionRepository implements SessionRepository {
    private final JdbcTemplate jdbcTemplate;

    JdbcSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SessionView> findAll() {
        return jdbcTemplate.query("""
                SELECT id, agent_role, status, observability_level, effective_model,
                       latest_system_prompt, recording_status
                FROM hearth_session
                ORDER BY created_at DESC
                """, (rs, rowNum) -> loadSession(rs));
    }

    @Override
    public Optional<SessionView> findById(UUID sessionId) {
        var sessions = jdbcTemplate.query("""
                SELECT id, agent_role, status, observability_level, effective_model,
                       latest_system_prompt, recording_status
                FROM hearth_session
                WHERE id = ?
                """, (rs, rowNum) -> loadSession(rs), sessionId);
        return sessions.stream().findFirst();
    }

    private SessionView loadSession(ResultSet rs) throws SQLException {
        UUID sessionId = rs.getObject("id", UUID.class);
        return new SessionView(
                sessionId,
                rs.getString("agent_role"),
                rs.getString("status"),
                rs.getString("observability_level"),
                rs.getString("effective_model"),
                rs.getString("latest_system_prompt"),
                rs.getString("recording_status"),
                loadTranscript(sessionId),
                loadExchanges(sessionId));
    }

    private List<TranscriptTurn> loadTranscript(UUID sessionId) {
        return jdbcTemplate.query("""
                SELECT role, content, created_at
                FROM hearth_transcript_turn
                WHERE session_id = ?
                ORDER BY ordinal
                """, (rs, rowNum) -> new TranscriptTurn(
                rs.getString("role"), rs.getString("content"), rs.getObject("created_at", OffsetDateTime.class).toInstant()), sessionId);
    }

    private List<ExchangeView> loadExchanges(UUID sessionId) {
        return jdbcTemplate.query("""
                SELECT id, requested_model, effective_model, input_tokens, output_tokens,
                       recording_status, recording_gap_reason, latency_ms
                FROM hearth_exchange
                WHERE session_id = ?
                ORDER BY created_at
                """, (rs, rowNum) -> new ExchangeView(
                rs.getObject("id", UUID.class),
                rs.getString("requested_model"),
                rs.getString("effective_model"),
                nullableLong(rs, "input_tokens"),
                nullableLong(rs, "output_tokens"),
                rs.getString("recording_status"),
                rs.getString("recording_gap_reason"),
                nullableLong(rs, "latency_ms")), sessionId);
    }

    private static long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
    }
}
