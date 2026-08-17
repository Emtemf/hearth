package ai.hearth.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class InvocationController {
    private final InteractiveInvocationService invocationService;
    private final JdbcInvocationRepository invocations;

    InvocationController(InteractiveInvocationService invocationService, JdbcInvocationRepository invocations) {
        this.invocationService = invocationService;
        this.invocations = invocations;
    }

    @PostMapping("/{sessionId}/invocations")
    ResponseEntity<Map<String, Object>> invoke(
            @PathVariable("sessionId") UUID sessionId,
            @RequestBody InvokeRequest request) {
        var assistant = invocationService.invoke(sessionId, request.content(), request.commandId());
        var response = new LinkedHashMap<String, Object>();
        response.put("data", Map.of(
                "sessionId", sessionId,
                "commandId", request.commandId() == null ? UUID.randomUUID() : request.commandId(),
                "status", "semantic_completed",
                "assistantContent", assistant));
        response.put("error", null);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{sessionId}/invocations")
    Map<String, Object> list(@PathVariable("sessionId") UUID sessionId) {
        return Map.of("data", Map.of("sessionId", sessionId, "content", invocations.findBySession(sessionId)), "error", (Object) null);
    }
}
