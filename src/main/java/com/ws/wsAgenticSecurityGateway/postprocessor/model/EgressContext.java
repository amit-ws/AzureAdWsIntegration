package com.ws.wsAgenticSecurityGateway.postprocessor.model;

import java.util.UUID;

/**
 * The governance context of one response hop, captured on the REQUEST thread and handed to the async classifier.
 * Carries everything the classification row needs so the classifier never has to read TenantContext (not
 * propagated to the async pool) or the audit row (which may not be saved yet).
 *
 * @param tenant          resolved tenant (passed explicitly — the async executor does not carry TenantContext)
 * @param correlationId   per-leg id — the reliable join key to the audit event for this same call
 * @param traceId         request-scoped umbrella id — the join key for the whole journey / DAG overlay
 * @param sourceEventId   the audit row's id when known (best-effort exact pointer); null is fine (correlationId links)
 * @param protocol        {@code MCP} or {@code A2A}
 * @param capabilityType  {@code TOOL | SKILL | PROMPT | RESOURCE}
 * @param capabilityName  the public capability name
 * @param producer        who produced the response (the tool's server / the downstream agent)
 * @param consumer        who receives it (the calling agent / next hop)
 */
public record EgressContext(
        String tenant,
        String correlationId,
        String traceId,
        UUID sourceEventId,
        String protocol,
        String capabilityType,
        String capabilityName,
        String producer,
        String consumer,
        // Exact identities (no name-guessing) so per-agent / per-tool rollups join deterministically:
        UUID consumerAgentId,       // the calling agent's registry id (getAgentIdForSession)
        String producerKind,        // SERVER (tool/prompt/resource) or AGENT (skill)
        UUID producerServerId,      // the MCP server's id (CapabilityDescriptor.serverId), when SERVER
        UUID producerAgentId) {     // the downstream agent's registry id (resolveAgentIdByName), when AGENT
}
