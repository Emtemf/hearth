package ai.hearth.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
class JdbcInvocationRepository {
    private final JdbcTemplate jdbcTemplate;

    JdbcInvocationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    InvocationSummary create(UUID sessionId, UUID invocationId, UUID commandId, String content) {
        jdbcTemplate.update("""
                INSERT INTO hearth_invocation(id, session_id, command_id, content, status, transport_status)
                VALUES (?, ?, ?, ?, 'PENDING', 'not_started')
                """, invocationId, sessionId, commandId, content);
        return findById(invocationId);
    }

    void markRunning(UUID invocationId) {
        jdbcTemplate.update("UPDATE hearth_invocation SET status = 'RUNNING', transport_status = 'connected', updated_at = now() WHERE id = ?", invocationId);
    }

    void complete(UUID invocationId, String assistantContent) {
        jdbcTemplate.update("""
                UPDATE hearth_invocation
                SET status = 'SEMANTIC_COMPLETED', assistant_content = ?, semantic_completed_at = now(),
                    transport_status = 'closed', updated_at = now()
                WHERE id = ?
                """, assistantContent, invocationId);
    }

    void fail(UUID invocationId, String reason) {
        jdbcTemplate.update("""
                UPDATE hearth_invocation
                SET status = 'FAILED', transport_status = ?, updated_at = now()
                WHERE id = ?
                """, reason, invocationId);
    }

    List<InvocationSummary> findBySession(UUID sessionId) {
        return jdbcTemplate.query("""
                SELECT id, command_id, status, content, assistant_content, created_at, semantic_completed_at
                FROM hearth_invocation WHERE session_id = ? ORDER BY created_at DESC
                """, (rs, rowNum) -> new InvocationSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("command_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("status"),
                rs.getString("content"),
                rs.getString("assistant_content"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("semantic_completed_at", OffsetDateTime.class) == null ? null : rs.getObject("semantic_completed_at", OffsetDateTime.class).toInstant()), sessionId);
    }

    InvocationSummary findById(UUID invocationId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, command_id, status, content, assistant_content, created_at, semantic_completed_at
                FROM hearth_invocation WHERE id = ?
                """, (rs, rowNum) -> new InvocationSummary(
                rs.getObject("id", UUID.class), rs.getObject("command_id", UUID.class),
                rs.getObject("session_id", UUID.class), rs.getString("status"),
                rs.getString("content"), rs.getString("assistant_content"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("semantic_completed_at", OffsetDateTime.class) == null ? null : rs.getObject("semantic_completed_at", OffsetDateTime.class).toInstant()), invocationId);
    }
}
