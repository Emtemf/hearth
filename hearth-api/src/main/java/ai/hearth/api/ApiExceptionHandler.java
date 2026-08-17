package ai.hearth.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ApiExceptionHandler {

    @ExceptionHandler(GatewayConfigurationException.class)
    org.springframework.http.ResponseEntity<Map<String, Object>> gatewayConfiguration(GatewayConfigurationException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    @ExceptionHandler(GatewayRequestException.class)
    org.springframework.http.ResponseEntity<Map<String, Object>> gatewayRequest(GatewayRequestException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        var body = new LinkedHashMap<String, Object>();
        body.put("data", null);
        body.put("error", Map.of("code", code, "message", "Request could not be completed."));
        return org.springframework.http.ResponseEntity.status(status).body(body);
    }
}
