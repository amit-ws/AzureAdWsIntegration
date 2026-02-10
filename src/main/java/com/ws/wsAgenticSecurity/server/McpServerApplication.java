package com.ws.wsAgenticSecurity.server;

import com.ws.wsAgenticSecurity.audit.service.McpAuditService;
import com.ws.wsAgenticSecurity.registry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurity.registry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurity.server.session.SessionManager;
import com.ws.wsAgenticSecurity.server.transport.ServerTransportProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.McpSyncServerExchange;
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
 *   <li>AI Agent → {@code tools/call} → placeholder (Orchestration Layer pending)</li>
 * </ol>
 *
 * <p><strong>Note:</strong> Actual tool call forwarding to enterprise servers
 * will be implemented by the Orchestration Layer in a future iteration.
 */
@Component
@Slf4j
@Order(2)
public class McpServerApplication implements ApplicationRunner {

    private final CapabilityRegistryService registryService;
    private final McpAuditService auditService;

    private McpSyncServer server;
    private SessionManager sessionManager;

    public McpServerApplication(CapabilityRegistryService registryService,
                                McpAuditService auditService) {
        this.registryService = registryService;
        this.auditService = auditService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("═══════════════════════════════════════════════════════════");
            log.info("🚀 WS MCP SERVER INITIALIZATION STARTED");
            log.info("═══════════════════════════════════════════════════════════");

            // Session manager for tracking AI client sessions (passes audit service to sessions)
            sessionManager = new SessionManager(auditService);

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

                // Tool call handler — placeholder until Orchestration Layer is built
                serverBuilder.toolCall(toolDefinition, (exchange, request) ->
                        handleToolCall(exchange, request, descriptor));

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
     * Placeholder tool call handler.
     *
     * <p>Receives AI agent tool calls, audits the invocation, and returns a
     * placeholder response indicating that the Orchestration Layer is pending.
     *
     * <p><strong>Future:</strong> The Orchestration Layer will replace this handler
     * to forward tool calls through the registry to the appropriate enterprise
     * MCP server via the WS MCP Client.
     */
    private McpSchema.CallToolResult handleToolCall(McpSyncServerExchange exchange,
                                                    McpSchema.CallToolRequest request,
                                                    CapabilityDescriptor descriptor) {
        String toolName = request.name();
        long start = System.currentTimeMillis();

        log.info("🔧 AI Agent invoked tool: {} (maps to {}.{})",
                toolName, descriptor.getServerConfigName(), descriptor.getOriginalName());

        try {
            // Get session ID for audit context
            String sessionId = null;
            try {
                sessionId = sessionManager.getCurrentSession().getSessionId();
            } catch (Exception e) {
                log.debug("Could not get session ID for audit: {}", e.getMessage());
            }

            // Audit the server-side tool invocation
            auditService.auditServerToolInvocation(
                    sessionId,
                    toolName,
                    request.arguments(),
                    null,
                    System.currentTimeMillis() - start);

            // Placeholder response — actual forwarding deferred to Orchestration Layer
            String message = String.format(
                    "Tool '%s' (server: %s, original: %s) received the request. " +
                    "Orchestration layer is not yet implemented — tool call forwarding is pending.",
                    toolName, descriptor.getServerConfigName(), descriptor.getOriginalName());

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(message)),
                    false
            );

        } catch (Exception e) {
            log.error("❌ Error handling tool call '{}': {}", toolName, e.getMessage(), e);
            return new McpSchema.CallToolResult(
                    "Error: " + e.getMessage(),
                    true
            );
        }
    }
}
