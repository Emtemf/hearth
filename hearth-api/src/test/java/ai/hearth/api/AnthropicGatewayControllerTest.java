package ai.hearth.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "hearth.gateway.capability=test-capability",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class AnthropicGatewayControllerTest {
    private static final UUID SESSION_ID = UUID.fromString("c0a80171-0000-4000-8000-000000000001");
    private static HttpServer upstream;
    private static volatile byte[] receivedBody;

    static {
        try {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/v1/messages", exchange -> {
                receivedBody = exchange.getRequestBody().readAllBytes();
                var response = "data: {\"type\":\"message_start\"}\n\n"
                        + "data: {\"type\":\"message\",\"usage\":{\"input_tokens\":7,\"output_tokens\":3},\"stop_reason\":\"end_turn\"}\n\n"
                        + "data: [DONE]\n\n";
                var bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            upstream.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("hearth.gateway.anthropic.upstream-base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        registry.add("hearth.gateway.anthropic.api-key", () -> "test-upstream-key");
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @AfterEach
    void clearBody() {
        receivedBody = null;
    }

    @Test
    void givenCapabilityAndUpstreamSse_whenForwarded_thenStreamsAndPreservesRequest() throws Exception {
        var body = "{\"model\":\"claude-opus-5\",\"system\":\"system prompt\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/s/{sessionId}/anthropic/v1/messages", SESSION_ID)
                .header("Authorization", "Bearer test-capability")
                .header("x-api-key", "test-upstream-key")
                .header("anthropic-version", "2023-06-01")
                .contentType("application/json")
                .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("message_start", "input_tokens");
        assertThat(new String(receivedBody, StandardCharsets.UTF_8)).isEqualTo(body);
    }
}
