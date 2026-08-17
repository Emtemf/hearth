package ai.hearth.api;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class InvocationLifecycleService {
    private final JdbcTemplate jdbcTemplate;

    InvocationLifecycleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void cancel(UUID invocationId) {
        jdbcTemplate.update("""
                UPDATE hearth_invocation
                SET status = CASE WHEN status IN ('PENDING', 'RUNNING') THEN 'CANCELLING' ELSE status END,
                    transport_status = 'cancel_requested', updated_at = now()
                WHERE id = ?
                """, invocationId);
    }

    InvocationSummary find(UUID invocationId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, command_id, status, content, assistant_content, created_at, semantic_completed_at
                FROM hearth_invocation WHERE id = ?
                """, (rs, rowNum) -> new InvocationSummary(
                rs.getObject("id", UUID.class), rs.getObject("command_id", UUID.class), rs.getString("status"),
                rs.getString("content"), rs.getString("assistant_content"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("semantic_completed_at", OffsetDateTime.class) == null
                        ? null : rs.getObject("semantic_completed_at", OffsetDateTime.class).toInstant()), invocationId);
    }
}
