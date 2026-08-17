package ai.hearth.worker.spike;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ChildEnvironment {
    private final Map<String, String> allowedValues;

    ChildEnvironment(Map<String, String> parentEnvironment, Map<String, String> allowedValues) {
        Objects.requireNonNull(parentEnvironment, "parentEnvironment");
        this.allowedValues = Map.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
    }

    Map<String, String> forSession(String gatewayCapability, String gatewayBaseUrl) {
        if (gatewayCapability == null || gatewayCapability.isBlank()) {
            throw new IllegalArgumentException("worker.gateway_capability.required");
        }
        if (gatewayBaseUrl == null || gatewayBaseUrl.isBlank()) {
            throw new IllegalArgumentException("worker.gateway_base_url.required");
        }
        var environment = new LinkedHashMap<>(allowedValues);
        environment.put("ANTHROPIC_AUTH_TOKEN", gatewayCapability);
        environment.put("ANTHROPIC_BASE_URL", gatewayBaseUrl);
        return Map.copyOf(environment);
    }
}
