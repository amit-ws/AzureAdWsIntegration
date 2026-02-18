package com.ws.wsAgenticSecurityGateway.wsServer;

import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.orchestration.ToolCallOrchestrator;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.wsServer.session.SessionManager;
import com.ws.wsAgenticSecurityGateway.wsServer.transport.ServerTransportProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
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
 *   <li>AI Agent → {@code prompts/get} → {@link ToolCallOrchestrator} forwards to enterprise server</li>
 *   <li>AI Agent → {@code resources/read} → {@link ToolCallOrchestrator} forwards to enterprise server</li>
 * </ol>
 *
 * <p>All capability requests (tools, prompts, resources) are delegated to the
 * {@link ToolCallOrchestrator}, which handles registry lookup, request forwarding
 * via WS MCP Client, audit logging, and in-flight request tracking.
 */
@Component
@Slf4j
@Order(2)
public class McpServerApplication implements ApplicationRunner {

    private final CapabilityRegistryService registryService;
    private final McpAuditService auditService;
    private final ToolCallOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    private McpSyncServer server;
    private SessionManager sessionManager;

    public McpServerApplication(CapabilityRegistryService registryService,
                                McpAuditService auditService,
                                ToolCallOrchestrator orchestrator,
                                ObjectMapper objectMapper) {
        this.registryService = registryService;
        this.auditService = auditService;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
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

            // ── Register tools from registry ────────────────────────────
            List<McpServerFeatures.SyncToolSpecification> toolSpecs = new ArrayList<>();
            for (CapabilityDescriptor descriptor : toolDescriptors) {
                McpSchema.Tool toolDefinition = McpSchema.Tool.builder()
                        .name(descriptor.getPublicName())
                        .description(descriptor.getDescription())
                        .inputSchema(descriptor.getInputSchema())
                        .build();

                toolSpecs.add(new McpServerFeatures.SyncToolSpecification(toolDefinition, null, this::handleToolCall));
                log.info("   🔧 Registered tool: {} (from server '{}')", descriptor.getPublicName(), descriptor.getServerConfigName());
            }
            if (!toolSpecs.isEmpty()) {
                serverBuilder.tools(toolSpecs);
            }

            // ── Register prompts from registry ──────────────────────────
            List<CapabilityDescriptor> promptDescriptors = registryService.getPromptDescriptors();
            if (!promptDescriptors.isEmpty()) {
                List<McpServerFeatures.SyncPromptSpecification> promptSpecs = new ArrayList<>();

                for (CapabilityDescriptor descriptor : promptDescriptors) {
                    // Parse prompt arguments from JSON string stored in registry
                    List<McpSchema.PromptArgument> promptArgs = parsePromptArguments(descriptor.getArguments());

                    McpSchema.Prompt promptDef = new McpSchema.Prompt(
                            descriptor.getPublicName(),
                            descriptor.getDescription(),
                            promptArgs);

                    promptSpecs.add(new McpServerFeatures.SyncPromptSpecification(promptDef, this::handleGetPrompt));
                    log.info("   📝 Registered prompt: {} (from server '{}')", descriptor.getPublicName(), descriptor.getServerConfigName());
                }

                serverBuilder.prompts(promptSpecs);
                log.info("🗂️  Registered {} prompts with MCP server", promptSpecs.size());
            }

            // ── Register resources from registry ────────────────────────
            List<CapabilityDescriptor> resourceDescriptors = registryService.getResourceDescriptors();
            if (!resourceDescriptors.isEmpty()) {
                List<McpServerFeatures.SyncResourceSpecification> resourceSpecs = new ArrayList<>();

                for (CapabilityDescriptor descriptor : resourceDescriptors) {
                    McpSchema.Resource resourceDef = McpSchema.Resource.builder()
                            .uri(descriptor.getResourceUri())
                            .name(descriptor.getPublicName())
                            .description(descriptor.getDescription())
                            .mimeType(descriptor.getMimeType())
                            .build();

                    resourceSpecs.add(new McpServerFeatures.SyncResourceSpecification(resourceDef, this::handleReadResource));
                    log.info("   📁 Registered resource: {} → {} (from server '{}')", descriptor.getPublicName(), descriptor.getResourceUri(), descriptor.getServerConfigName());
                }

                serverBuilder.resources(resourceSpecs);
                log.info("🗂️  Registered {} resources with MCP server", resourceSpecs.size());
            }

            // Build the server
            server = serverBuilder.build();

            log.info("═══════════════════════════════════════════════════════════");
            log.info("✅ WS MCP SERVER STARTED SUCCESSFULLY");
            log.info("   Server: ws-mcp-gateway v1.0.0");
            log.info("   Tools:     {} (from {} enterprise servers)",
                    toolDescriptors.size(), registryService.getRegisteredServerNames().size());
            log.info("   Prompts:   {} (registered with SDK)", promptDescriptors.size());
            log.info("   Resources: {} (registered with SDK)", resourceDescriptors.size());
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

    /**
     * Prompt get handler — delegates to the {@link ToolCallOrchestrator}.
     *
     * <p>The orchestrator performs registry lookup to resolve the public prompt name
     * to the enterprise server + original prompt name, then forwards the request.
     */
    private McpSchema.GetPromptResult handleGetPrompt(McpSyncServerExchange exchange,
                                                      McpSchema.GetPromptRequest request) {
        return orchestrator.orchestrateGetPrompt(exchange, request.name(), request.arguments());
    }

    /**
     * Resource read handler — delegates to the {@link ToolCallOrchestrator}.
     *
     * <p>The orchestrator performs registry lookup by resource URI to resolve to
     * the enterprise server, then forwards the read request.
     */
    private McpSchema.ReadResourceResult handleReadResource(McpSyncServerExchange exchange,
                                                            McpSchema.ReadResourceRequest request) {
        // Look up the resource by URI to find the public name for orchestration
        String publicName = resolvePublicNameByUri(request.uri());
        return orchestrator.orchestrateReadResource(exchange, publicName, request.uri());
    }

    /**
     * Resolve the public name of a resource by its URI.
     * Resources are registered with the SDK by URI, but our registry indexes by publicName.
     * This does a reverse lookup from URI → publicName.
     */
    private String resolvePublicNameByUri(String uri) {
        List<CapabilityDescriptor> resources = registryService.getResourceDescriptors();
        for (CapabilityDescriptor descriptor : resources) {
            if (uri.equals(descriptor.getResourceUri())) {
                return descriptor.getPublicName();
            }
        }
        // If not found by exact URI match, use the URI itself as the identifier
        return uri;
    }

    /**
     * Parse prompt arguments from JSON string stored in the capability registry.
     * Returns empty list if the JSON is null, blank, or unparseable.
     */
    private List<McpSchema.PromptArgument> parsePromptArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(argumentsJson,
                    new TypeReference<List<McpSchema.PromptArgument>>() {
                    });
        } catch (Exception e) {
            log.warn("Failed to parse prompt arguments JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
