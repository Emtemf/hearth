package ai.hearth.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "false", matchIfMissing = true)
class DemoSessionRepository implements SessionRepository {
    private static final UUID DEMO_SESSION_ID = UUID.fromString("c0a80171-0000-4000-8000-000000000001");
    private static final UUID DEMO_EXCHANGE_ID = UUID.fromString("c0a80171-0000-4000-8000-000000000002");
    private static final Instant DEMO_STARTED_AT = Instant.parse("2026-08-17T01:00:00Z");
    private static final SessionView DEMO_SESSION = new SessionView(
            DEMO_SESSION_ID,
            "coder",
            "READY",
            "full",
            "claude-opus-5",
            "You are the Hearth coding agent. Work within the approved workspace.",
            "complete",
            List.of(
                    new TranscriptTurn("user", "Inspect the M1 implementation status.", DEMO_STARTED_AT),
                    new TranscriptTurn("assistant", "The M1 runnable surface is available.", DEMO_STARTED_AT.plusSeconds(2))),
            List.of(new ExchangeView(
                    DEMO_EXCHANGE_ID,
                    "claude-opus-5",
                    "claude-opus-5",
                    128L,
                    42L,
                    "complete",
                    null,
                    250L)));

    @Override
    public List<SessionView> findAll() {
        return List.of(DEMO_SESSION);
    }

    @Override
    public Optional<SessionView> findById(UUID sessionId) {
        return DEMO_SESSION.id().equals(sessionId) ? Optional.of(DEMO_SESSION) : Optional.empty();
    }
}
