package ai.hearth.api;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

record CreateSessionRequest(String agentRole, String model, String systemPrompt) {}

record InvokeRequest(String content, UUID commandId) {}

record InteractiveSession(UUID id, String gatewayCapability) {}
