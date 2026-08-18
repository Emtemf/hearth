package ai.hearth.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/s/{sessionId}/anthropic")
final class AnthropicGatewayController {
    private final AnthropicUpstreamClient upstreamClient;
    private final SessionRepository sessionRepository;
    private final ExchangeRecorder exchangeRecorder;
    private final String gatewayCapability;

    AnthropicGatewayController(
            AnthropicUpstreamClient upstreamClient,
            SessionRepository sessionRepository,
            org.springframework.beans.factory.ObjectProvider<ExchangeRecorder> exchangeRecorderProvider,
            @Value("${hearth.gateway.capability:}") String gatewayCapability) {
        this.upstreamClient = upstreamClient;
        this.sessionRepository = sessionRepository;
        this.exchangeRecorder = exchangeRecorderProvider.getIfAvailable();
        this.gatewayCapability = gatewayCapability;
    }

    @PostMapping(value = "/v1/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StreamingResponseBody> messages(
            @PathVariable("sessionId") UUID sessionId,
            @RequestHeader(value = "anthropic-version", defaultValue = "2023-06-01") String anthropicVersion,
            @RequestHeader(value = "authorization", defaultValue = "") String authorization,
            @RequestBody byte[] requestBody) throws IOException, InterruptedException {
        sessionRepository.findById(sessionId).orElseThrow(() -> new GatewayRequestException("gateway.session_not_found"));
        if (gatewayCapability.isBlank() || !authorization.equals("Bearer " + gatewayCapability)) {
            throw new GatewayRequestException("gateway.capability_invalid");
        }
        var exchange = exchangeRecorder == null ? null : exchangeRecorder.start(sessionId, requestBody);
        var upstream = upstreamClient.forward(requestBody, anthropicVersion);
        var responseBody = (StreamingResponseBody) outputStream -> {
            try (var input = upstream.body()) {
                var capture = new ByteArrayOutputStream();
                var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    outputStream.write(buffer, 0, read);
                    outputStream.flush();
                    capture.write(buffer, 0, read);
                }
                if (exchange != null) {
                    exchangeRecorder.complete(exchange, capture.toByteArray(), upstream.statusCode(), true);
                }
            } catch (IOException exception) {
                if (exchange != null) {
                    exchangeRecorder.markFailed(exchange.id(), "storage_failure");
                }
                throw exception;
            }
        };
        return ResponseEntity.status(upstream.statusCode())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(responseBody);
    }
}

final class GatewayRequestException extends RuntimeException {
    GatewayRequestException(String message) {
        super(message);
    }
}
