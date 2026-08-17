package ai.hearth.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SessionRepository {

    List<SessionView> findAll();

    Optional<SessionView> findById(UUID sessionId);
}
