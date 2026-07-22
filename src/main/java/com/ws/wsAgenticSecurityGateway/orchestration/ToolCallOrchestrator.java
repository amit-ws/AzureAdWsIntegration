package com.ws.wsAgenticSecurityGateway.orchestration;

import com.ws.wsAgenticSecurityGateway.orchestration.adapter.McpAdapter;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityType;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import com.ws.wsAgenticSecurityGateway.wsServer.session.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Thin compatibility facade over the {@link HopOrchestrator} spine.
 *
 * <p>Preserves the exact public surface that the MCP transport initializers
 * ({@code HttpMcpServerInitializer}, {@code McpServerApplication}) call, so those classes
 * need no change. Each method builds a {@link Hop} and delegates to the spine, which runs the
 * governance lifecycle and dispatches through the MCP adapter.
 *
 * <p>{@link #setSessionManager} is retained here (forwarding to both the spine and the MCP
 * adapter) because the {@code SessionManager} is created at runtime by
 * {@code McpServerApplication} and wired through this call site — keeping that wiring unchanged.
 */
@Service
@Slf4j
public class ToolCallOrchestrator {

    private final HopOrchestrator hopOrchestrator;
    private final McpAdapter mcpAdapter;

    public ToolCallOrchestrator(HopOrchestrator hopOrchestrator, McpAdapter mcpAdapter) {
        this.hopOrchestrator = hopOrchestrator;
        this.mcpAdapter = mcpAdapter;
    }

    /** Wires the runtime-created SessionManager into the spine and the MCP adapter. */
    public void setSessionManager(SessionManager sessionManager) {
        hopOrchestrator.setSessionManager(sessionManager);
        mcpAdapter.setSessionManager(sessionManager);
    }

    public McpSchema.CallToolResult orchestrate(McpSyncServerExchange exchange,
                                                String publicName,
                                                Map<String, Object> arguments) {
        Hop hop = new Hop(CapabilityType.TOOL, publicName, arguments, null, exchange);
        return (McpSchema.CallToolResult) hopOrchestrator.handle(hop);
    }

    public McpSchema.GetPromptResult orchestrateGetPrompt(McpSyncServerExchange exchange,
                                                          String publicName,
                                                          Map<String, Object> arguments) {
        Hop hop = new Hop(CapabilityType.PROMPT, publicName, arguments, null, exchange);
        return (McpSchema.GetPromptResult) hopOrchestrator.handle(hop);
    }

    public McpSchema.ReadResourceResult orchestrateReadResource(McpSyncServerExchange exchange,
                                                               String publicName,
                                                               String resourceUri) {
        Hop hop = new Hop(CapabilityType.RESOURCE, publicName, null, resourceUri, exchange);
        return (McpSchema.ReadResourceResult) hopOrchestrator.handle(hop);
    }
}
