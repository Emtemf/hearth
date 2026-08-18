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
    private final String authToken;

    AnthropicUpstreamClient(
            @Value("${hearth.gateway.anthropic.upstream-base-url:http://127.0.0.1:4599}") String upstreamBaseUrl,
            @Value("${hearth.gateway.anthropic.api-key:${ANTHROPIC_API_KEY:}}") String apiKey,
            @Value("${hearth.gateway.anthropic.auth-token:${ANTHROPIC_AUTH_TOKEN:}}") String authToken) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.upstreamBaseUri = URI.create(upstreamBaseUrl);
        this.apiKey = apiKey;
        this.authToken = authToken;
    }

    HttpResponse<java.io.InputStream> forward(byte[] requestBody, String anthropicVersion) throws IOException, InterruptedException {
        if (apiKey.isBlank() && authToken.isBlank()) {
            throw new GatewayConfigurationException("gateway.upstream_credential_not_configured");
        }
        var requestBuilder = HttpRequest.newBuilder(upstreamBaseUri.resolve("/v1/messages"))
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("anthropic-version", anthropicVersion);
        if (!apiKey.isBlank()) {
            requestBuilder.header("x-api-key", apiKey);
        } else {
            requestBuilder.header("authorization", "Bearer " + authToken);
        }
        var request = requestBuilder
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
