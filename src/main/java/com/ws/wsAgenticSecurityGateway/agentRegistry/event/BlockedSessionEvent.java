package com.ws.wsAgenticSecurityGateway.agentRegistry.event;

public record BlockedSessionEvent(
        String sessionId,
        String identityType,
        String identityName
) {}
