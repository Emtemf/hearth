package ai.hearth.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class InteractiveSessionController {
    private final InteractiveSessionService service;
    private final InteractiveInvocationService invocationService;
    private final JdbcInvocationRepository invocationRepository;

    InteractiveSessionController(
            InteractiveSessionService service,
            InteractiveInvocationService invocationService,
            JdbcInvocationRepository invocationRepository) {
        this.service = service;
        this.invocationService = invocationService;
        this.invocationRepository = invocationRepository;
    }

    @PostMapping
    Map<String, Object> create(@RequestBody CreateSessionRequest request) {
        var session = service.create(request);
        var data = new LinkedHashMap<String, Object>();
        data.put("id", session.id());
        data.put("gatewayBaseUrl", "/s/" + session.id() + "/anthropic");
        data.put("effectiveModel", session.effectiveModel());
        data.put("providerConfigured", true);
        return success(data);
    }

    @GetMapping("/{sessionId}/invocations")
    Map<String, Object> invocations(@PathVariable("sessionId") UUID sessionId) {
        var response = new LinkedHashMap<String, Object>();
        response.put("data", Map.of("sessionId", sessionId, "content", invocationRepository.findBySession(sessionId)));
        response.put("error", null);
        return response;
    }
    @PostMapping("/{sessionId}/invocations")
    ResponseEntity<Map<String, Object>> invocation(
            @PathVariable("sessionId") UUID sessionId,
            @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
            @RequestBody InvokeRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().body(success(Map.of("status", "content_required")));
        }
        var response = invocationService.invoke(sessionId, request.content(), request.commandId());
        return ResponseEntity.ok(success(Map.of(
                "sessionId", sessionId,
                "commandId", request.commandId() == null ? UUID.randomUUID() : request.commandId(),
                "status", "semantic_completed",
                "assistantContent", response)));
    }

    private Map<String, Object> success(Object data) {
        var response = new LinkedHashMap<String, Object>();
        response.put("data", data);
        response.put("error", null);
        return response;
    }
}
