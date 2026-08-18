package ai.hearth.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M1DatabaseEvidenceTest {
    @Test
    void givenPersistedSession_whenApiQueriesDatabase_thenTwoTurnsAndExchangesRemainAfterReconnect() throws Exception {
        var sessionId = UUID.randomUUID();
        var exchangeOne = UUID.randomUUID();
        var exchangeTwo = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:55432/hearth", "hearth", "hearth")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO hearth_session(id, agent_role, status, observability_level,
                            effective_model, latest_system_prompt, recording_status)
                        VALUES ('%s', 'coder', 'READY', 'full', 'gpt-5.6-terra', 'M1 evidence prompt', 'complete')
                        """.formatted(sessionId));
                statement.executeUpdate("""
                        INSERT INTO hearth_transcript_turn(session_id, ordinal, role, content, created_at)
                        VALUES ('%s', 1, 'user', 'first', now()), ('%s', 2, 'assistant', 'one', now()),
                               ('%s', 3, 'user', 'second', now()), ('%s', 4, 'assistant', 'two', now())
                        """.formatted(sessionId, sessionId, sessionId, sessionId));
                statement.executeUpdate("""
                        INSERT INTO hearth_exchange(id, session_id, requested_model, effective_model,
                            input_tokens, output_tokens, recording_status)
                        VALUES ('%s', '%s', 'gpt-5.6-terra', 'gpt-5.6-terra', 10, 3, 'complete'),
                               ('%s', '%s', 'gpt-5.6-terra', 'gpt-5.6-terra', 22, 5, 'complete')
                        """.formatted(exchangeOne, sessionId, exchangeTwo, sessionId));
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT count(*) FROM hearth_transcript_turn WHERE session_id = '" + sessionId + "'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT count(*) FROM hearth_exchange WHERE session_id = '" + sessionId + "'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
        }
        try (Connection reconnect = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:55432/hearth", "hearth", "hearth");
                var statement = reconnect.createStatement();
                var rows = statement.executeQuery("SELECT latest_system_prompt FROM hearth_session WHERE id = '" + sessionId + "'")) {
            rows.next();
            assertThat(rows.getString(1)).isEqualTo("M1 evidence prompt");
        }
    }
}
