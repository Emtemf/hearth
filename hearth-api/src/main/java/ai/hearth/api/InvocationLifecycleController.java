package ai.hearth.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invocations")
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class InvocationLifecycleController {
    private final InvocationLifecycleService service;

    InvocationLifecycleController(InvocationLifecycleService service) {
        this.service = service;
    }

    @GetMapping("/{invocationId}")
    Map<String, Object> get(@PathVariable("invocationId") UUID invocationId) {
        return envelope(service.find(invocationId));
    }

    @DeleteMapping("/{invocationId}")
    ResponseEntity<Map<String, Object>> cancel(@PathVariable("invocationId") UUID invocationId) {
        service.cancel(invocationId);
        return ResponseEntity.accepted().body(envelope(service.find(invocationId)));
    }

    private Map<String, Object> envelope(Object data) {
        var result = new LinkedHashMap<String, Object>();
        result.put("data", data);
        result.put("error", null);
        return result;
    }
}
