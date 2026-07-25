package com.ws.wsAgenticSecurityGateway.orchestration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityResult;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;

import java.time.LocalDateTime;

/**
 * The protocol dispatch seam — the swap point where the MCP adapter (now) or an A2A
 * adapter (later) plugs in.
 *
 * <p>The dispatch methods return a protocol-neutral {@link CapabilityResult}: the adapter performs the
 * downstream call and packages the wire result as an opaque {@code payload} plus neutral audit metadata,
 * so the spine ({@code HopOrchestrator}) can audit and return it without referencing any protocol SDK.
 * The governance lifecycle around the call (capability check, registry lookup, PDP, connection check,
 * in-flight tracking, audit, error shaping) lives in the spine; the adapter only performs the downstream
 * protocol call and applies/clears the per-target downstream credentials. The protocol's inbound facade
 * maps the {@code payload} back to its own wire type.
 */
public interface ProtocolAdapter {

    /** Invoke a tool on the resolved downstream target; payload carries the protocol's tool-result content. */
    CapabilityResult callTool(Hop hop, JsonNode argsJson, String correlationId,
                              LocalDateTime firedAt, int eventSequence);

    /** Fetch a prompt from the resolved downstream target; payload carries the protocol's prompt result. */
    CapabilityResult getPrompt(Hop hop);

    /**
     * Read a resource from the resolved downstream target ({@code hop.originalName()} holds the uri);
     * payload carries the protocol's resource-contents result.
     */
    CapabilityResult readResource(Hop hop, String correlationId,
                                  LocalDateTime firedAt, int eventSequence);

    /** Applies protocol-specific downstream credentials for this hop; returns true if any were applied. */
    boolean applyCredentials(Hop hop, String correlationId);

    /** Clears any credentials applied by {@link #applyCredentials(Hop, String)}. */
    void clearCredentials(String correlationId);

    /**
     * The protocol this adapter speaks ({@code "MCP"}, {@code "A2A"}). The spine tags each hop with it, so
     * protocol-scoped artifacts (OBO token scope, audit {@code protocol} column) are attributed correctly
     * without the spine hardcoding any single protocol.
     */
    String protocol();

    /**
     * Whether the downstream target is currently connected/reachable via this protocol. The spine calls
     * this as a pre-flight check without knowing how the adapter manages its connections — an A2A adapter
     * answers for its own peers, an MCP adapter for its server sessions.
     */
    boolean isTargetConnected(String targetName);

    /**
     * The gateway's downstream session id for the target (for audit correlation), or {@code null} if the
     * protocol has no session concept or none is currently established. Never throws.
     */
    String downstreamSessionId(String targetName);
}
