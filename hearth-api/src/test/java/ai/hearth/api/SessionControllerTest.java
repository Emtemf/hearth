package ai.hearth.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class SessionControllerTest {
    private static final String SESSION_ID = "c0a80171-0000-4000-8000-000000000001";

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void givenDemoSession_whenListed_thenReturnsSessionSummary() throws Exception {
        mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(SESSION_ID));
    }

    @Test
    void givenDemoSession_whenTranscriptRequested_thenReturnsPromptAndTurns() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/transcript", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemPrompt").value("You are the Hearth coding agent. Work within the approved workspace."))
                .andExpect(jsonPath("$.data.turns.length()").value(2))
                .andExpect(jsonPath("$.data.recordingStatus").value("complete"));
    }

    @Test
    void givenUnknownSession_whenDetailRequested_thenReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/c0a80171-0000-4000-8000-000000000099"))
                .andExpect(status().isNotFound());
    }
}
