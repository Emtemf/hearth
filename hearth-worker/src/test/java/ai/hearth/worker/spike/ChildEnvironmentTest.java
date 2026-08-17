package ai.hearth.worker.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ChildEnvironmentTest {

    @Test
    void givenParentSecrets_whenBuilt_thenRemovesSecretsAndAddsOnlySessionCapability() {
        var parent = Map.of(
                "PATH", "/usr/bin",
                "LANG", "C.UTF-8",
                "ANTHROPIC_API_KEY", "provider-secret",
                "HEARTH_DB_PASSWORD", "database-secret",
                "FEISHU_APP_SECRET", "im-secret",
                "HEARTH_ADMIN_TOKEN", "admin-secret");

        var environment = new ChildEnvironment(parent, Map.of("PATH", "/usr/bin", "LANG", "C.UTF-8"))
                .forSession("gateway-capability", "http://127.0.0.1:4517/s/session-id/anthropic");

        assertThat(environment).containsEntry("ANTHROPIC_AUTH_TOKEN", "gateway-capability");
        assertThat(environment).containsEntry("ANTHROPIC_BASE_URL", "http://127.0.0.1:4517/s/session-id/anthropic");
        assertThat(environment).doesNotContainKeys(
                "ANTHROPIC_API_KEY", "HEARTH_DB_PASSWORD", "FEISHU_APP_SECRET", "HEARTH_ADMIN_TOKEN");
    }

    @Test
    void givenArbitraryParentVariable_whenBuilt_thenDoesNotInheritIt() {
        var environment = new ChildEnvironment(
                        Map.of("PATH", "/usr/bin", "UNSAFE_PARENT_VALUE", "should-not-leak"),
                        Map.of("PATH", "/usr/bin"))
                .forSession("gateway-capability", "http://127.0.0.1:4517/s/session-id/anthropic");

        assertThat(environment).doesNotContainKey("UNSAFE_PARENT_VALUE");
    }
}
