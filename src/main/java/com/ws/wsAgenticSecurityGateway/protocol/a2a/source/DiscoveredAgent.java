package com.ws.wsAgenticSecurityGateway.protocol.a2a.source;

/**
 * A downstream agent as reported by an {@link AgentSource} — the minimal handle the gateway needs to make an
 * agent callable: a stable name and the base URL of its A2A endpoint. Deliberately source-neutral, so the DB
 * (self-describe) and a platform control plane (platform-sync) describe agents the same way.
 *
 * @param name    the gateway-local agent name (the prefix its skills register under)
 * @param baseUrl the agent's A2A base URL (its Agent Card is at {@code /.well-known/agent-card.json})
 * @param source  which source reported it — {@code "self-describe"}, {@code "kore-platform"}, … (for logging)
 */
public record DiscoveredAgent(String name, String baseUrl, String source) {
}
