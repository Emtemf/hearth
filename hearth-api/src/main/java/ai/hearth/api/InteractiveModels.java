package ai.hearth.api;

import java.util.UUID;

record CreateSessionRequest(String agentRole, String model, String systemPrompt) {}

record InvokeRequest(String content) {}

record InteractiveSession(UUID id, String gatewayCapability) {}
