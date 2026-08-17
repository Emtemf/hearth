package ai.hearth.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class AnthropicUpstreamClient {
    private final HttpClient httpClient;
    private final URI upstreamBaseUri;
    private final String apiKey;

    AnthropicUpstreamClient(
            @Value("${hearth.gateway.anthropic.upstream-base-url:http://127.0.0.1:4599}") String upstreamBaseUrl,
            @Value("${ANTHROPIC_API_KEY:}") String apiKey) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.upstreamBaseUri = URI.create(upstreamBaseUrl);
        this.apiKey = apiKey;
    }

    HttpResponse<java.io.InputStream> forward(byte[] requestBody, String anthropicVersion) throws IOException, InterruptedException {
        if (apiKey.isBlank()) {
            throw new GatewayConfigurationException("gateway.upstream_credential_not_configured");
        }
        var request = HttpRequest.newBuilder(upstreamBaseUri.resolve("/v1/messages"))
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("anthropic-version", anthropicVersion)
                .header("x-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }
}

final class GatewayConfigurationException extends RuntimeException {
    GatewayConfigurationException(String message) {
        super(message);
    }
}
