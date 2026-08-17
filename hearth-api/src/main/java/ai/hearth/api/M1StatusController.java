package ai.hearth.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/m1")
final class M1StatusController {

    @GetMapping("/status")
    Map<String, Object> status() {
        var response = new LinkedHashMap<String, Object>();
        response.put("data", Map.of(
                "milestone", "M1",
                "surface", "runnable",
                "workerContract", "fixture_verified",
                "processReusePolicy", "STREAMING_STDIN",
                "eventTypes", List.of("system", "assistant", "result", "assistant", "result"),
                "realCliProbe", "not_run"));
        response.put("error", null);
        return response;
    }
}
