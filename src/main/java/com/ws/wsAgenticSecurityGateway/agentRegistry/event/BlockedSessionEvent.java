package com.ws.wsAgenticSecurityGateway.agentRegistry.event;

/**
 * Spring application event published when an admin blocks an identity (human/NHI/agent)
 * and active sessions need to be flagged for immediate rejection in {@code HttpMcpAuditFilter}.
 *
 * <p>Decouples the service layer (publisher) from the filter layer (listener)
 * to avoid circular dependency between AgentRegistryService and HttpMcpAuditFilter.
 */
public record BlockedSessionEvent(
        String sessionId,
        String identityType,    // "HUMAN", "NHI", or "AGENT"
        String identityName     // username, service name, or agent name
) {}
