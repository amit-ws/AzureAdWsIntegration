package com.ws.wsAgenticSecurityGateway.wsServer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.orchestration.ToolCallOrchestrator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WS MCP Server — HTTP Streamable mode.
 *
 * <p>Builds the same MCP server (tools, prompts, resources from Capability Registry)
 * but uses {@link HttpServletStreamableServerTransportProvider} instead of stdio.
 * The servlet is registered by {@code HttpTransportConfig}.
 *
 * <p>Key difference from {@link McpServerApplication} (stdio mode):
 * <ul>
 *   <li>No {@code Thread.currentThread().join()} — the servlet container handles HTTP requests</li>
 *   <li>No {@code SessionManager} — the SDK manages per-agent sessions internally</li>
 *   <li>Multiple agents can connect simultaneously</li>
 *   <li>Southbound MCP connections persist across agent reconnects</li>
 * </ul>
 *
 * <p>{@code @Order(2)} ensures this runs <strong>after</strong> the MCP Client
 * initializer ({@code @Order(1)}) so the capability registry is populated.
 */
@Component
@Slf4j
@Order(2)
@ConditionalOnProperty(name = "ws.gateway.transport", havingValue = "http", matchIfMissing = true)
public class HttpMcpServerInitializer implements ApplicationRunner {

    private final HttpServletStreamableServerTransportProvider transportProvider;
    private final CapabilityRegistryService registryService;
    private final ToolCallOrchestrator orchestrator;
    private final AgentRegistryService agentRegistryService;
    private final McpAuditService auditService;
    private final GatewayAgentSessionRepository agentSessionRepository;
    private final ObjectMapper objectMapper;

    /** Tracks which HTTP sessions have been checked for fallback registration. */
    private final ConcurrentHashMap<String, Boolean> checkedSessions = new ConcurrentHashMap<>();

    private McpSyncServer server;

    public HttpMcpServerInitializer(HttpServletStreamableServerTransportProvider transportProvider,
                                     CapabilityRegistryService registryService,
                                     ToolCallOrchestrator orchestrator,
                                     AgentRegistryService agentRegistryService,
                                     McpAuditService auditService,
                                     GatewayAgentSessionRepository agentSessionRepository,
                                     ObjectMapper objectMapper) {
        this.transportProvider = transportProvider;
        this.registryService = registryService;
        this.orchestrator = orchestrator;
        this.agentRegistryService = agentRegistryService;
        this.auditService = auditService;
        this.agentSessionRepository = agentSessionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("═══════════════════════════════════════════════════════════");
            log.info("🚀 WS MCP SERVER INITIALIZATION (HTTP MODE)");
            log.info("═══════════════════════════════════════════════════════════");

            // ── Configure server capabilities ──────────────────────────
            McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .resources(false, false)
                    .prompts(true)
                    .build();

            // ── Build MCP server using HTTP Streamable transport ────────
            var serverBuilder = McpServer.sync(transportProvider)
                    .serverInfo("ws-mcp-gateway", "1.0.0")
                    .capabilities(capabilities);

            // ── Register tools from capability registry ─────────────────
            List<CapabilityDescriptor> toolDescriptors = registryService.getToolDescriptors();
            List<McpServerFeatures.SyncToolSpecification> toolSpecs = new ArrayList<>();

            for (CapabilityDescriptor descriptor : toolDescriptors) {
                McpSchema.Tool toolDef = McpSchema.Tool.builder()
                        .name(descriptor.getPublicName())
                        .description(descriptor.getDescription())
                        .inputSchema(descriptor.getInputSchema())
                        .build();

                toolSpecs.add(new McpServerFeatures.SyncToolSpecification(
                        toolDef, null, this::handleToolCall));
                log.info("   🔧 Registered tool: {} (from '{}')",
                        descriptor.getPublicName(), descriptor.getServerConfigName());
            }
            if (!toolSpecs.isEmpty()) {
                serverBuilder.tools(toolSpecs);
            }

            // ── Register prompts from capability registry ────────────────
            List<CapabilityDescriptor> promptDescriptors = registryService.getPromptDescriptors();
            if (!promptDescriptors.isEmpty()) {
                List<McpServerFeatures.SyncPromptSpecification> promptSpecs = new ArrayList<>();

                for (CapabilityDescriptor descriptor : promptDescriptors) {
                    List<McpSchema.PromptArgument> promptArgs =
                            parsePromptArguments(descriptor.getArguments());

                    McpSchema.Prompt promptDef = new McpSchema.Prompt(
                            descriptor.getPublicName(),
                            descriptor.getDescription(),
                            promptArgs);

                    promptSpecs.add(new McpServerFeatures.SyncPromptSpecification(
                            promptDef, this::handleGetPrompt));
                    log.info("   📝 Registered prompt: {} (from '{}')",
                            descriptor.getPublicName(), descriptor.getServerConfigName());
                }
                serverBuilder.prompts(promptSpecs);
            }

            // ── Register resources from capability registry ──────────────
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

                    resourceSpecs.add(new McpServerFeatures.SyncResourceSpecification(
                            resourceDef, this::handleReadResource));
                    log.info("   📁 Registered resource: {} → {} (from '{}')",
                            descriptor.getPublicName(), descriptor.getResourceUri(),
                            descriptor.getServerConfigName());
                }
                serverBuilder.resources(resourceSpecs);
            }

            // ── Build the server ─────────────────────────────────────────
            server = serverBuilder.build();

            log.info("═══════════════════════════════════════════════════════════");
            log.info("✅ WS MCP SERVER STARTED (HTTP MODE)");
            log.info("   Server: ws-mcp-gateway v1.0.0");
            log.info("   Transport: HTTP Streamable at /mcp");
            log.info("   Tools:     {} (from {} enterprise servers)",
                    toolDescriptors.size(), registryService.getRegisteredServerNames().size());
            log.info("   Prompts:   {}", promptDescriptors.size());
            log.info("   Resources: {}", resourceDescriptors.size());
            log.info("   Ready for agent connections via HTTP");
            log.info("═══════════════════════════════════════════════════════════");

            // NO Thread.currentThread().join() — servlet container handles requests

        } catch (Exception e) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("❌ WS MCP SERVER (HTTP) INITIALIZATION FAILED");
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Error: {}", e.getMessage(), e);
        }
    }

    /**
     * Graceful shutdown — closes all HTTP agent sessions.
     */
    @PreDestroy
    public void shutdown() {
        log.info("🛑 WS MCP Server (HTTP) shutting down...");
        if (server != null) {
            try {
                server.closeGracefully();
                log.info("✅ HTTP server shutdown complete");
            } catch (Exception e) {
                log.error("Error during HTTP server shutdown: {}", e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  FALLBACK AGENT REGISTRATION — primary registration happens in
    //  HttpMcpAuditFilter on initialize. This is a safety net for edge
    //  cases where the filter missed the initialize request.
    // ════════════════════════════════════════════════════════════════════

    /**
     * Fallback: ensures the agent is registered if the {@code HttpMcpAuditFilter}
     * missed the initialize. Checks the DB first to avoid duplicate session rows.
     */
    private void ensureAgentRegistered(McpSyncServerExchange exchange) {
        String sessionId = exchange.sessionId();
        if (sessionId == null || checkedSessions.containsKey(sessionId)) {
            return;
        }
        if (checkedSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            return;
        }

        try {
            // Check if the filter already registered this session
            if (agentSessionRepository.findBySessionId(sessionId).isPresent()) {
                log.debug("Session {} already registered by audit filter", sessionId);
                return;
            }

            // Filter missed it — do fallback registration
            McpSchema.Implementation clientInfo = exchange.getClientInfo();
            McpSchema.ClientCapabilities clientCaps = exchange.getClientCapabilities();
            String agentName = clientInfo != null ? clientInfo.name() : "unknown";
            String agentVersion = clientInfo != null ? clientInfo.version() : null;

            JsonNode capsJson = clientCaps != null ? objectMapper.valueToTree(clientCaps) : null;

            GatewayAgentEntity agent = agentRegistryService.discoverAgent(
                    agentName, agentVersion, null, capsJson);
            agentRegistryService.registerSession(agent.getId(), sessionId, "HTTP", null);
            auditService.auditServerSessionInitialized(
                    sessionId, null, clientInfo, clientCaps, null, agentName);

            log.info("HTTP agent session registered (fallback): {} (agent={})", sessionId, agentName);

        } catch (Exception e) {
            log.error("Fallback agent registration failed for {}: {}", sessionId, e.getMessage());
            checkedSessions.remove(sessionId);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  HANDLERS — delegate to ToolCallOrchestrator (same as stdio mode)
    // ════════════════════════════════════════════════════════════════════

    private McpSchema.CallToolResult handleToolCall(McpSyncServerExchange exchange,
                                                     McpSchema.CallToolRequest request) {
        ensureAgentRegistered(exchange);
        return orchestrator.orchestrate(exchange, request.name(), request.arguments());
    }

    private McpSchema.GetPromptResult handleGetPrompt(McpSyncServerExchange exchange,
                                                       McpSchema.GetPromptRequest request) {
        ensureAgentRegistered(exchange);
        return orchestrator.orchestrateGetPrompt(exchange, request.name(), request.arguments());
    }

    private McpSchema.ReadResourceResult handleReadResource(McpSyncServerExchange exchange,
                                                             McpSchema.ReadResourceRequest request) {
        ensureAgentRegistered(exchange);
        String publicName = resolvePublicNameByUri(request.uri());
        return orchestrator.orchestrateReadResource(exchange, publicName, request.uri());
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    private String resolvePublicNameByUri(String uri) {
        List<CapabilityDescriptor> resources = registryService.getResourceDescriptors();
        for (CapabilityDescriptor descriptor : resources) {
            if (uri.equals(descriptor.getResourceUri())) {
                return descriptor.getPublicName();
            }
        }
        return uri;
    }

    private List<McpSchema.PromptArgument> parsePromptArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(argumentsJson,
                    new TypeReference<List<McpSchema.PromptArgument>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse prompt arguments JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
