package com.ws.wsAgenticSecurityGateway.protocol.mcp.inbound;
import com.ws.wsAgenticSecurityGateway.orchestration.HopOrchestrator;

import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityResult;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityType;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The MCP inbound facade over the protocol-neutral {@link HopOrchestrator} spine.
 *
 * <p>Preserves the exact public surface that the MCP transport initializers
 * ({@code HttpMcpServerInitializer}, {@code StdioMcpServerInitializer}) call, so those classes
 * need no change. Each method builds a {@link Hop}, delegates to the spine (which runs the governance
 * lifecycle and dispatches through the MCP adapter), and maps the neutral {@link CapabilityResult} back
 * into the MCP wire type. This mapping is the one place MCP result types are reconstructed — the A2A
 * inbound facade will map the same {@code CapabilityResult} into its own shapes.
 */
@Service
@Slf4j
public class ToolCallOrchestrator {

    private final HopOrchestrator hopOrchestrator;

    public ToolCallOrchestrator(HopOrchestrator hopOrchestrator) {
        this.hopOrchestrator = hopOrchestrator;
    }

    @SuppressWarnings("unchecked")
    public McpSchema.CallToolResult orchestrate(McpSyncServerExchange exchange,
                                                String publicName,
                                                Map<String, Object> arguments) {
        Hop hop = new Hop(CapabilityType.TOOL, publicName, arguments, null, exchange);
        CapabilityResult result = hopOrchestrator.handle(hop);
        if (result.error()) {
            return new McpSchema.CallToolResult(result.summary(), true);
        }
        return new McpSchema.CallToolResult((List<McpSchema.Content>) result.payload(), false);
    }

    public McpSchema.GetPromptResult orchestrateGetPrompt(McpSyncServerExchange exchange,
                                                          String publicName,
                                                          Map<String, Object> arguments) {
        Hop hop = new Hop(CapabilityType.PROMPT, publicName, arguments, null, exchange);
        // Prompt errors surface as exceptions from the spine (not error results), so a returned
        // result is always a successful GetPromptResult payload.
        return (McpSchema.GetPromptResult) hopOrchestrator.handle(hop).payload();
    }

    @SuppressWarnings("unchecked")
    public McpSchema.ReadResourceResult orchestrateReadResource(McpSyncServerExchange exchange,
                                                               String publicName,
                                                               String resourceUri) {
        Hop hop = new Hop(CapabilityType.RESOURCE, publicName, null, resourceUri, exchange);
        // Resource errors surface as exceptions from the spine (not error results).
        CapabilityResult result = hopOrchestrator.handle(hop);
        return new McpSchema.ReadResourceResult((List<McpSchema.ResourceContents>) result.payload());
    }
}
