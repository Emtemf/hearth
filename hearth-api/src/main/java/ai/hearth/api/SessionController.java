package ai.hearth.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/sessions")
final class SessionController {
    private final SessionRepository sessionRepository;

    SessionController(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @GetMapping
    Map<String, Object> sessions() {
        var summaries = sessionRepository.findAll().stream().map(session -> Map.of(
                "id", session.id(),
                "agentRole", session.agentRole(),
                "status", session.status(),
                "observabilityLevel", session.observabilityLevel(),
                "effectiveModel", session.effectiveModel(),
                "inputTokens", session.exchanges().stream().mapToLong(ExchangeView::inputTokens).sum(),
                "outputTokens", session.exchanges().stream().mapToLong(ExchangeView::outputTokens).sum())).toList();
        return success(Map.of("content", summaries, "totalElements", summaries.size()));
    }

    @GetMapping("/{sessionId}")
    Map<String, Object> sessionDetail(@PathVariable("sessionId") UUID sessionId) {
        var session = requireSession(sessionId);
        return success(Map.of(
                "id", session.id(),
                "agentRole", session.agentRole(),
                "status", session.status(),
                "observabilityLevel", session.observabilityLevel(),
                "effectiveModel", session.effectiveModel(),
                "latestSystemPrompt", session.latestSystemPrompt(),
                "latestSystemPromptRecordingStatus", session.recordingStatus()));
    }

    @GetMapping("/{sessionId}/transcript")
    Map<String, Object> transcript(@PathVariable("sessionId") UUID sessionId) {
        var session = requireSession(sessionId);
        var turns = session.transcript().stream().map(turn -> Map.of(
                "role", turn.role(), "content", turn.content(), "createdAt", turn.createdAt())).toList();
        return success(Map.of(
                "sessionId", session.id(),
                "turns", turns,
                "systemPrompt", session.latestSystemPrompt(),
                "totalExchanges", session.exchanges().size(),
                "recordingStatus", session.recordingStatus(),
                "gaps", List.of()));
    }

    @GetMapping("/{sessionId}/exchanges")
    Map<String, Object> exchanges(@PathVariable("sessionId") UUID sessionId) {
        var session = requireSession(sessionId);
        var exchanges = session.exchanges().stream().map(exchange -> {
            var response = new LinkedHashMap<String, Object>();
            response.put("id", exchange.id());
            response.put("requestedModel", exchange.requestedModel());
            response.put("effectiveModel", exchange.effectiveModel());
            response.put("inputTokens", exchange.inputTokens());
            response.put("outputTokens", exchange.outputTokens());
            response.put("recordingStatus", exchange.recordingStatus());
            response.put("recordingGapReason", exchange.recordingGapReason());
            response.put("latencyMs", exchange.latencyMs());
            return response;
        }).toList();
        return success(Map.of("sessionId", session.id(), "content", exchanges));
    }

    private SessionView requireSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session.not_found"));
    }

    private Map<String, Object> success(Object data) {
        var response = new LinkedHashMap<String, Object>();
        response.put("data", data);
        response.put("error", null);
        return response;
    }
}
