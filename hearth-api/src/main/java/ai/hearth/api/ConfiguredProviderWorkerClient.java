package ai.hearth.api;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class ConfiguredProviderWorkerClient implements ApiWorkerClient {
    private final AnthropicUpstreamClient upstream;
    private final UUID launchId = UUID.randomUUID();

    ConfiguredProviderWorkerClient(AnthropicUpstreamClient upstream) {
        this.upstream = upstream;
    }

    @Override
    public WorkerInvocationResult invoke(
            UUID sessionId, UUID invocationId, String content, String model, String systemPrompt) {
        try {
            var body = new com.fasterxml.jackson.databind.json.JsonMapper().createObjectNode();
            body.put("model", model);
            body.put("system", systemPrompt);
            body.put("stream", true);
            var messages = body.putArray("messages");
            messages.addObject().put("role", "user").put("content", content);
            var response = upstream.forward(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "2023-06-01");
            try (var input = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    return new WorkerInvocationResult("", "upstream_rejected");
                }
                return new WorkerInvocationResult(
                        extract(input.readAllBytes()), "connected");
            }
        } catch (java.io.IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return new WorkerInvocationResult("", "transport_failed");
        }
    }

    @Override
    public WorkerCancellationResult cancel(UUID invocationId) {
        return new WorkerCancellationResult("CANCELLED", "provider_request_closed");
    }

    private String extract(byte[] bytes) throws java.io.IOException {
        var output = new StringBuilder();
        for (var line : new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
            if (!line.startsWith("data: ") || line.substring(6).equals("[DONE]")) continue;
            var node = new com.fasterxml.jackson.databind.json.JsonMapper().readTree(line.substring(6));
            if (node.path("delta").path("text").isTextual()) output.append(node.path("delta").path("text").asText());
            if (node.path("content").isArray()) node.path("content").forEach(block -> { if (block.path("text").isTextual()) output.append(block.path("text").asText()); });
        }
        return output.toString();
    }
}
