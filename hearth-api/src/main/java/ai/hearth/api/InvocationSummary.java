package ai.hearth.api;

import java.time.Instant;
import java.util.UUID;

record InvocationSummary(
        UUID id,
        UUID commandId,
        String status,
        String content,
        String assistantContent,
        Instant createdAt,
        Instant semanticCompletedAt) {}
