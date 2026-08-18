package ai.hearth.api;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

record CreateSessionRequest(String agentRole, String model, String systemPrompt, String upstreamBaseUrl, String upstreamCredential) {}

record InvokeRequest(String content, UUID commandId) {}

record InteractiveSession(UUID id, String gatewayCapability, String upstreamBaseUrl, String effectiveModel) {}
