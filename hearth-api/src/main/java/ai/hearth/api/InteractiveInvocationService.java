package ai.hearth.api;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class InteractiveInvocationService {
    private final SessionRepository sessions;
    private final InteractiveSessionService sessionService;
    private final ApiWorkerClient workerClient;
    private final JdbcInvocationRepository invocations;
    private final InvocationEventHub eventHub;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    InteractiveInvocationService(
            SessionRepository sessions,
            InteractiveSessionService sessionService,
            ApiWorkerClient workerClient,
            JdbcInvocationRepository invocations,
            InvocationEventHub eventHub) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.workerClient = workerClient;
        this.invocations = invocations;
        this.eventHub = eventHub;
    }

    String invoke(UUID sessionId, String content, UUID commandId) {
        var session = sessions.findById(sessionId).orElseThrow(() -> new GatewayRequestException("session.not_found"));
        var invocationId = UUID.randomUUID();
        invocations.create(sessionId, invocationId, commandId, content);
        invocations.markRunning(invocationId);
        eventHub.publish(new InvocationEvent(invocationId, sessionId, "RUNNING", null));
        sessionService.appendUserTurn(sessionId, content);
        try {
            var workerResult = workerClient.invoke(sessionId, invocationId, content, session.effectiveModel(), session.latestSystemPrompt());
            if (workerResult.assistantContent().isBlank()) {
                invocations.fail(invocationId, workerResult.transportStatus());
                eventHub.publish(new InvocationEvent(invocationId, sessionId, "FAILED", null));
                throw new GatewayRequestException("gateway.invocation_failed");
            }
            var assistant = workerResult.assistantContent();
            sessionService.appendAssistantTurn(sessionId, assistant);
            invocations.complete(invocationId, assistant);
            eventHub.publish(new InvocationEvent(invocationId, sessionId, "SEMANTIC_COMPLETED", assistant));
            return assistant;
        } catch (RuntimeException exception) {
            invocations.fail(invocationId, "worker_failed");
            eventHub.publish(new InvocationEvent(invocationId, sessionId, "FAILED", null));
            throw exception;
        }
    }

    private String extractAssistantText(byte[] payload) throws IOException {
        var text = new String(payload, StandardCharsets.UTF_8);
        var response = new StringBuilder();
        for (var line : text.lines().toList()) {
            if (!line.startsWith("data: ")) {
                continue;
            }
            var data = line.substring(6);
            if (data.equals("[DONE]")) {
                continue;
            }
            var node = jsonMapper.readTree(data);
            var deltaText = node.path("delta").path("text");
            if (deltaText.isTextual()) {
                response.append(deltaText.asText());
            }
        }
        return response.isEmpty() ? "The upstream completed without text content." : response.toString();
    }
}
