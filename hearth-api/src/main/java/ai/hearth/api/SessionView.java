package ai.hearth.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record SessionView(
        UUID id,
        String agentRole,
        String status,
        String observabilityLevel,
        String effectiveModel,
        String latestSystemPrompt,
        String recordingStatus,
        List<TranscriptTurn> transcript,
        List<ExchangeView> exchanges) {}

record TranscriptTurn(String role, String content, Instant createdAt) {}

record ExchangeView(
        UUID id,
        String requestedModel,
        String effectiveModel,
        long inputTokens,
        long outputTokens,
        String recordingStatus,
        String recordingGapReason,
        long latencyMs) {}
