package ai.hearth.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class AnthropicExchangeParser {
    private final ObjectMapper objectMapper;

    AnthropicExchangeParser() {
        this.objectMapper = JsonMapper.builder().build();
    }

    ParsedExchange parseRequest(byte[] requestBody) throws IOException {
        JsonNode root = objectMapper.readTree(requestBody);
        var systemPrompt = extractSystem(root.path("system"));
        var messages = root.path("messages");
        return new ParsedExchange(
                systemPrompt,
                messages.isArray() ? messages.size() : 0,
                root.path("model").asText(null),
                canonicalTurns(messages));
    }

    ParsedResponse parseResponse(byte[] responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        var usage = root.path("usage");
        return new ParsedResponse(
                usage.path("input_tokens").asLong(0),
                usage.path("output_tokens").asLong(0),
                root.path("stop_reason").asText(null));
    }

    private String extractSystem(JsonNode system) {
        if (system.isTextual()) {
            return system.asText();
        }
        if (!system.isArray()) {
            return null;
        }
        var blocks = new ArrayList<String>();
        system.forEach(block -> {
            if (block.path("text").isTextual()) {
                blocks.add(block.path("text").asText());
            }
        });
        return String.join("\n", blocks);
    }

    private List<TranscriptTurn> canonicalTurns(JsonNode messages) {
        if (!messages.isArray()) {
            return List.of();
        }
        var turns = new ArrayList<TranscriptTurn>();
        messages.forEach(message -> turns.add(new TranscriptTurn(
                message.path("role").asText("unknown"),
                contentText(message.path("content")),
                null)));
        return List.copyOf(turns);
    }

    private String contentText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return content.toString();
        }
        var values = new ArrayList<String>();
        content.forEach(block -> {
            if (block.path("text").isTextual()) {
                values.add(block.path("text").asText());
            } else {
                values.add(block.toString());
            }
        });
        return String.join("\n", values);
    }
}

record ParsedExchange(String systemPrompt, int messageCount, String requestedModel, List<TranscriptTurn> turns) {}

record ParsedResponse(long inputTokens, long outputTokens, String stopReason) {}
