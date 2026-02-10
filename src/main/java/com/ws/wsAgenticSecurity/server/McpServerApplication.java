package com.ws.wsAgenticSecurity.server;

import com.ws.wsAgenticSecurity.audit.service.McpAuditService;
import com.ws.wsAgenticSecurity.orchestration.ToolCallOrchestrator;
import com.ws.wsAgenticSecurity.registry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurity.registry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurity.server.session.SessionManager;
import com.ws.wsAgenticSecurity.server.transport.ServerTransportProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WS MCP Server — exposes all registered capabilities (tools, resources, prompts)
 * from the Capability Registry to AI agents via the MCP protocol.
 *
 * <p>{@code @Order(2)} ensures this runs <strong>after</strong> the MCP Client
 * initializer ({@code @Order(1)}) so that the registry is populated before
 * the server exposes tools to AI agents.
 *
 * <h3>AI Agent Flow</h3>
 * <ol>
 *   <li>AI Agent → {@code initialize} → WS Server (handled by MCP SDK)</li>
 *   <li>AI Agent → {@code tools/list} → returns all tools from registry</li>
 *   <li>AI Agent → {@code resources/list} → returns all resources from registry</li>
 *   <li>AI Agent → {@code prompts/list} → returns all prompts from registry</li>
 *   <li>AI Agent → {@code tools/call} → {@link ToolCallOrchestrator} routes to enterprise server</li>
 * </ol>
 *
 * <p>Tool calls are delegated to the {@link ToolCallOrchestrator}, which handles
 * registry lookup, request forwarding via WS MCP Client, audit logging, and
 * in-flight request tracking.
 */
@Component
@Slf4j
@Order(2)
public class McpServerApplication implements ApplicationRunner {

    private final CapabilityRegistryService registryService;
    private final McpAuditService auditService;
    private final ToolCallOrchestrator orchestrator;

    private McpSyncServer server;
    private SessionManager sessionManager;

    public McpServerApplication(CapabilityRegistryService registryService,
                                McpAuditService auditService,
                                ToolCallOrchestrator orchestrator) {
        this.registryService = registryService;
        this.auditService = auditService;
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("═══════════════════════════════════════════════════════════");
            log.info("🚀 WS MCP SERVER INITIALIZATION STARTED");
            log.info("═══════════════════════════════════════════════════════════");

            // Session manager for tracking AI client sessions (passes audit service to sessions)
            sessionManager = new SessionManager(auditService);

            // Wire session manager into orchestrator (setter injection — SessionManager is a POJO, not a Spring bean)
            orchestrator.setSessionManager(sessionManager);

            // Create transport provider (stdio-based, passes audit service for request/response interception)
            ServerTransportProvider transportProvider = new ServerTransportProvider(sessionManager, auditService);

            // ── Get all tool descriptors from registry ─────────────────
            List<CapabilityDescriptor> toolDescriptors = registryService.getToolDescriptors();
            log.info("🗂️  Found {} tools in registry to expose", toolDescriptors.size());

            // ── Configure server capabilities ──────────────────────────
            McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                    .tools(true)     // Enable tools with list change notifications
                    .resources(false, false) // Enable resources listing
                    .prompts(true)  // Enable prompts listing
                    .build();

            // ── Build MCP server with dynamic tools from registry ──────
            var serverBuilder = McpServer.sync(transportProvider)
                    .serverInfo("ws-mcp-gateway", "1.0.0")
                    .capabilities(capabilities);

            // Register each tool from the registry
            for (CapabilityDescriptor descriptor : toolDescriptors) {
                McpSchema.Tool toolDefinition = McpSchema.Tool.builder()
                        .name(descriptor.getPublicName())
                        .description(descriptor.getDescription())
                        .inputSchema(descriptor.getInputSchema())
                        .build();

                // Tool call handler — delegates to ToolCallOrchestrator for full routing
                serverBuilder.toolCall(toolDefinition, (exchange, request) ->
                        handleToolCall(exchange, request));

                log.info("   🔧 Registered tool: {} (from server '{}')",
                        descriptor.getPublicName(), descriptor.getServerConfigName());
            }

            // Build the server
            server = serverBuilder.build();

            log.info("═══════════════════════════════════════════════════════════");
            log.info("✅ WS MCP SERVER STARTED SUCCESSFULLY");
            log.info("   Server: ws-mcp-gateway v1.0.0");
            log.info("   Tools:  {} (from {} enterprise servers)",
                    toolDescriptors.size(), registryService.getRegisteredServerNames().size());
            log.info("   Resources: {}", registryService.getResourceDescriptors().size());
            log.info("   Prompts:   {}", registryService.getPromptDescriptors().size());
            log.info("   Waiting for AI agent connection via stdio...");
            log.info("═══════════════════════════════════════════════════════════");

            // Print all registered tools
            if (!toolDescriptors.isEmpty()) {
                log.info("📋 REGISTERED TOOLS:");
                for (CapabilityDescriptor td : toolDescriptors) {
                    log.info("   - {} → {}.{} ({})",
                            td.getPublicName(),
                            td.getServerConfigName(),
                            td.getOriginalName(),
                            td.getDescription() != null ? td.getDescription() : "No description");
                }
            }

            // Keep server running
            Thread.currentThread().join();

        } catch (Exception e) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("❌ WS MCP SERVER INITIALIZATION FAILED");
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Error: {}", e.getMessage(), e);
            // Don't exit — allow Spring Boot application to continue running
        }
    }

    /**
     * Tool call handler — delegates to the {@link ToolCallOrchestrator}.
     *
     * <p>The orchestrator handles the full 10-step lifecycle:
     * registry lookup, in-flight tracking, request forwarding to the enterprise
     * MCP server via WS MCP Client, audit logging, and error handling.
     *
     * <p>The {@code exchange} carries the SDK's session context (session ID,
     * client info, capabilities) — passed through to the orchestrator for
     * audit logging and agent identification.
     *
     * <p>This method is synchronous — blocks until the enterprise server responds.
     * The MCP SDK wraps the returned {@link McpSchema.CallToolResult} in a
     * JSON-RPC response and sends it back to the AI agent via stdout.
     */
    private McpSchema.CallToolResult handleToolCall(McpSyncServerExchange exchange,
                                                    McpSchema.CallToolRequest request) {
        return orchestrator.orchestrate(exchange, request.name(), request.arguments());
    }
}
