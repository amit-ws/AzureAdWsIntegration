package com.ws.wsAgenticSecurityGateway.orchestration.model;

/**
 * The kind of capability a {@link Hop} targets. Protocol-agnostic: the spine
 * ({@code HopOrchestrator}) branches on this to select the PDP builder, the resolved
 * descriptor field, and the adapter dispatch method. Extensible per protocol — {@code SKILL} is the
 * A2A agent→agent unit, invoked like a {@code TOOL} but dispatched to a downstream agent.
 */
public enum CapabilityType {
    TOOL,
    PROMPT,
    RESOURCE,
    SKILL
}
