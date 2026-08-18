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
class M1StatusControllerTest {

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void givenRunningApplication_whenHealthRequested_thenReturnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void givenM1Surface_whenStatusRequested_thenReportsFixtureEvidence() throws Exception {
        mockMvc.perform(get("/api/v1/m1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.milestone").value("M1"))
                .andExpect(jsonPath("$.data.workerContract").value("fixture_verified"))
                .andExpect(jsonPath("$.data.realCliProbe").value("not_run"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()));
    }
}
