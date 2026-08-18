package ai.hearth.worker.runtime;

import java.util.UUID;

record WorkerEvent(UUID sessionId, UUID invocationId, String type, String content) {}
