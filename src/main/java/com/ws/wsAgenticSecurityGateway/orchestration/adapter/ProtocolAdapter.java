package com.ws.wsAgenticSecurityGateway.orchestration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The protocol dispatch seam — the swap point where the MCP adapter (now) or an A2A
 * adapter (later) plugs in.
 *
 * <p><b>Stage-0 scope:</b> the return types are MCP-typed by design. The governance
 * lifecycle around the call (capability check, registry lookup, PDP, connection check,
 * in-flight tracking, audit, error shaping) lives in the spine ({@code HopOrchestrator});
 * the adapter only performs the downstream protocol call and applies/clears the
 * per-target downstream credentials. The neutral cross-protocol result contract is
 * finalized in the A2A phase — see {@code docs/stage-0-refactor-plan.md} → "KEY SCOPE
 * DECISION" — so it is designed with a real second protocol in view rather than guessed.
 */
public interface ProtocolAdapter {

    /** Invoke a tool on the resolved downstream target. */
    List<McpSchema.Content> callTool(Hop hop, JsonNode argsJson, String correlationId,
                                     LocalDateTime firedAt, int eventSequence);

    /** Fetch a prompt from the resolved downstream target. */
    McpSchema.GetPromptResult getPrompt(Hop hop);

    /** Read a resource from the resolved downstream target ({@code hop.originalName()} holds the uri). */
    List<McpSchema.ResourceContents> readResource(Hop hop, String correlationId,
                                                  LocalDateTime firedAt, int eventSequence);

    /** Applies protocol-specific downstream credentials for this hop; returns true if any were applied. */
    boolean applyCredentials(Hop hop, String correlationId);

    /** Clears any credentials applied by {@link #applyCredentials(Hop, String)}. */
    void clearCredentials(String correlationId);
}
