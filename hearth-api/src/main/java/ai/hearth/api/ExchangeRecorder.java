package ai.hearth.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class ExchangeRecorder {
    private final JdbcTemplate jdbcTemplate;
    private final AnthropicExchangeParser parser;
    private final ObjectMapper objectMapper;

    ExchangeRecorder(JdbcTemplate jdbcTemplate, AnthropicExchangeParser parser) {
        this.jdbcTemplate = jdbcTemplate;
        this.parser = parser;
        this.objectMapper = JsonMapper.builder().build();
    }

    ExchangeRecording start(UUID sessionId, byte[] requestBody) {
        try {
            var request = parser.parseRequest(requestBody);
            var exchangeId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO hearth_exchange
                        (id, session_id, requested_model, effective_model, input_tokens,
                         output_tokens, recording_status, recording_gap_reason, latency_ms)
                    SELECT ?, id, ?, ?, NULL, NULL, 'partial', 'upstream_in_progress', NULL
                    FROM hearth_session WHERE id = ?
                    """, exchangeId, request.requestedModel(), request.requestedModel(), sessionId);
            jdbcTemplate.update("UPDATE hearth_session SET latest_system_prompt = ?, updated_at = now() WHERE id = ?",
                    request.systemPrompt(), sessionId);
            return new ExchangeRecording(exchangeId, Instant.now(), request);
        } catch (IOException exception) {
            throw new GatewayRequestException("gateway.request_parse_failed");
        }
    }

    void complete(ExchangeRecording exchange, byte[] responseBytes, int statusCode, boolean streamed) {
        try {
            var response = parser.parseResponse(lastDataPayload(responseBytes));
            jdbcTemplate.update("""
                    UPDATE hearth_exchange
                    SET status_code = ?, input_tokens = ?, output_tokens = ?, stop_reason = ?,
                        streamed = ?, recording_status = 'complete', recording_gap_reason = NULL,
                        latency_ms = ?, updated_at = now()
                    WHERE id = ?
                    """, statusCode, response.inputTokens(), response.outputTokens(), response.stopReason(), streamed,
                    DurationMillis.since(exchange.startedAt()), exchange.id());
        } catch (IOException exception) {
            markFailed(exchange.id(), "parse_failure");
        }
    }

    void markFailed(UUID exchangeId, String reason) {
        jdbcTemplate.update("""
                UPDATE hearth_exchange
                SET recording_status = 'failed', recording_gap_reason = ?, updated_at = now()
                WHERE id = ?
                """, reason, exchangeId);
    }

    private byte[] lastDataPayload(byte[] bytes) throws IOException {
        var text = new String(bytes, StandardCharsets.UTF_8);
        var payload = text.lines()
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring(6))
                .filter(line -> !line.equals("[DONE]"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IOException("gateway.response_data_missing"));
        JsonNode node = objectMapper.readTree(payload);
        return node.toString().getBytes(StandardCharsets.UTF_8);
    }
}

record ExchangeRecording(UUID id, Instant startedAt, ParsedExchange request) {}

final class DurationMillis {
    private DurationMillis() {}

    static long since(Instant startedAt) {
        return java.time.Duration.between(startedAt, Instant.now()).toMillis();
    }
}
