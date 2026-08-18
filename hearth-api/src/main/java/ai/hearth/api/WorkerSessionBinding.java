package ai.hearth.api;

import java.util.UUID;

record WorkerSessionBinding(UUID sessionId, long processGeneration, UUID launchId) {}
