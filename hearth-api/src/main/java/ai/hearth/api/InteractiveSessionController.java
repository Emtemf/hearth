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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class InteractiveSessionController {
    private final InteractiveSessionService service;

    InteractiveSessionController(InteractiveSessionService service) {
        this.service = service;
    }

    @PostMapping
    Map<String, Object> create(@RequestBody CreateSessionRequest request) {
        var session = service.create(request);
        return success(Map.of(
                "id", session.id(),
                "gatewayBaseUrl", "/s/" + session.id() + "/anthropic",
                "gatewayCapability", session.gatewayCapability()));
    }

    @PostMapping("/{sessionId}/messages")
    ResponseEntity<Map<String, Object>> message(
            @PathVariable("sessionId") UUID sessionId,
            @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
            @RequestBody InvokeRequest request) {
        service.appendUserTurn(sessionId, request.content());
        return ResponseEntity.accepted().body(success(Map.of("sessionId", sessionId, "status", "accepted")));
    }

    private Map<String, Object> success(Object data) {
        var response = new LinkedHashMap<String, Object>();
        response.put("data", data);
        response.put("error", null);
        return response;
    }
}
