package ai.hearth.api;

import java.util.UUID;

interface ApiWorkerClient {
    WorkerInvocationResult invoke(UUID sessionId, UUID invocationId, String content, String model, String systemPrompt);
    WorkerCancellationResult cancel(UUID invocationId);
}

record WorkerInvocationResult(String assistantContent, String transportStatus) {}
record WorkerCancellationResult(String state, String transportStatus) {}
