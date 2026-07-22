package com.ws.wsAgenticSecurityGateway.orchestration.model;

/**
 * The kind of MCP capability a {@link Hop} targets. Protocol-agnostic: the spine
 * ({@code HopOrchestrator}) branches on this to select the PDP builder, the resolved
 * descriptor field, and the adapter dispatch method.
 */
public enum CapabilityType {
    TOOL,
    PROMPT,
    RESOURCE
}
