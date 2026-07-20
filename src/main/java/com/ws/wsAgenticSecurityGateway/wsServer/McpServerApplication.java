package com.ws.wsAgenticSecurityGateway.wsServer;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.orchestration.ToolCallOrchestrator;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyContextBuilder;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.wsServer.session.SessionManager;
import com.ws.wsAgenticSecurityGateway.wsServer.session.SessionReaperService;
import com.ws.wsAgenticSecurityGateway.wsServer.transport.ServerTransportProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@Slf4j
@Order(2)
@ConditionalOnProperty(name = "ws.gateway.transport", havingValue = "stdio")
public class McpServerApplication implements ApplicationRunner {

    private final CapabilityRegistryService registryService;
    private final McpAuditService auditService;
    private final ToolCallOrchestrator orchestrator;
    private final AgentRegistryService agentRegistryService;
    private final ObjectMapper objectMapper;
    private final SessionReaperService sessionReaperService;
    private final PolicyContextBuilder policyContextBuilder;

    private McpSyncServer server;
    private SessionManager sessionManager;

    public McpServerApplication(CapabilityRegistryService registryService,
                                McpAuditService auditService,
                                ToolCallOrchestrator orchestrator,
                                AgentRegistryService agentRegistryService,
                                ObjectMapper objectMapper,
                                @Autowired(required = false) SessionReaperService sessionReaperService,
                                PolicyContextBuilder policyContextBuilder) {
        this.registryService = registryService;
        this.auditService = auditService;
        this.orchestrator = orchestrator;
        this.agentRegistryService = agentRegistryService;
        this.objectMapper = objectMapper;
        this.sessionReaperService = sessionReaperService;
        this.policyContextBuilder = policyContextBuilder;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
 log.info("WS MCP SERVER INITIALIZATION STARTED");

            sessionManager = new SessionManager(auditService);
            sessionManager.setAgentRegistryService(agentRegistryService);

            orchestrator.setSessionManager(sessionManager);
            if (policyContextBuilder != null) {
                policyContextBuilder.setSessionManager(sessionManager);
            }

            if (sessionReaperService != null) {
                sessionReaperService.setSessionManager(sessionManager);
                log.info("Session reaper wired with SessionManager");
            }

            ServerTransportProvider transportProvider = new ServerTransportProvider(sessionManager, auditService);

            List<CapabilityDescriptor> toolDescriptors = registryService.getToolDescriptors();
 log.info("Found {} tools in registry to expose", toolDescriptors.size());

            McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .resources(false, false)
                    .prompts(true)
                    .build();

            var serverBuilder = McpServer.sync(transportProvider)
                    .serverInfo("ws-mcp-gateway", "1.0.0")
                    .capabilities(capabilities);

            List<McpServerFeatures.SyncToolSpecification> toolSpecs = new ArrayList<>();
            for (CapabilityDescriptor descriptor : toolDescriptors) {
                McpSchema.Tool toolDefinition = McpSchema.Tool.builder()
                        .name(descriptor.getPublicName())
                        .description(descriptor.getDescription())
                        .inputSchema(descriptor.getInputSchema())
                        .build();

                toolSpecs.add(new McpServerFeatures.SyncToolSpecification(toolDefinition, null, this::handleToolCall));
 log.info("Registered tool: {} (from server '{}')", descriptor.getPublicName(), descriptor.getServerConfigName());
            }
            if (!toolSpecs.isEmpty()) {
                serverBuilder.tools(toolSpecs);
            }

            List<CapabilityDescriptor> promptDescriptors = registryService.getPromptDescriptors();
            if (!promptDescriptors.isEmpty()) {
                List<McpServerFeatures.SyncPromptSpecification> promptSpecs = new ArrayList<>();

                for (CapabilityDescriptor descriptor : promptDescriptors) {
                    List<McpSchema.PromptArgument> promptArgs = parsePromptArguments(descriptor.getArguments());

                    McpSchema.Prompt promptDef = new McpSchema.Prompt(
                            descriptor.getPublicName(),
                            descriptor.getDescription(),
                            promptArgs);

                    promptSpecs.add(new McpServerFeatures.SyncPromptSpecification(promptDef, this::handleGetPrompt));
 log.info("Registered prompt: {} (from server '{}')", descriptor.getPublicName(), descriptor.getServerConfigName());
                }

                serverBuilder.prompts(promptSpecs);
 log.info("Registered {} prompts with MCP server", promptSpecs.size());
            }

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
 log.info("Registered resource: {} → {} (from server '{}')", descriptor.getPublicName(), descriptor.getResourceUri(), descriptor.getServerConfigName());
                }

                serverBuilder.resources(resourceSpecs);
 log.info("Registered {} resources with MCP server", resourceSpecs.size());
            }

            server = serverBuilder.build();

 log.info("WS MCP SERVER STARTED SUCCESSFULLY");
            log.info("Server: ws-mcp-gateway v1.0.0");
            log.info("Tools:     {} (from {} enterprise servers)",
                    toolDescriptors.size(), registryService.getRegisteredServerNames().size());
            log.info("Prompts:   {} (registered with SDK)", promptDescriptors.size());
            log.info("Resources: {} (registered with SDK)", resourceDescriptors.size());
            log.info("Waiting for AI agent connection via stdio...");

            if (!toolDescriptors.isEmpty()) {
 log.info("REGISTERED TOOLS:");
                for (CapabilityDescriptor td : toolDescriptors) {
                    log.info("- {} → {}.{} ({})",
                            td.getPublicName(),
                            td.getServerConfigName(),
                            td.getOriginalName(),
                            td.getDescription() != null ? td.getDescription() : "No description");
                }
            }

            Thread.currentThread().join();

        } catch (Exception e) {
 log.error("WS MCP SERVER INITIALIZATION FAILED");
            log.error("Error: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
 log.info("WS MCP Server (stdio) shutting down...");
        if (server != null) {
            try {
                server.closeGracefully();
 log.info("Server shutdown complete");
            } catch (Exception e) {
                log.error("Error during server shutdown: {}", e.getMessage());
            }
        }
    }

    private McpSchema.CallToolResult handleToolCall(McpSyncServerExchange exchange,
                                                    McpSchema.CallToolRequest request) {
        return orchestrator.orchestrate(exchange, request.name(), request.arguments());
    }

    private McpSchema.GetPromptResult handleGetPrompt(McpSyncServerExchange exchange,
                                                      McpSchema.GetPromptRequest request) {
        return orchestrator.orchestrateGetPrompt(exchange, request.name(), request.arguments());
    }

    private McpSchema.ReadResourceResult handleReadResource(McpSyncServerExchange exchange,
                                                            McpSchema.ReadResourceRequest request) {
        String publicName = resolvePublicNameByUri(request.uri());
        return orchestrator.orchestrateReadResource(exchange, publicName, request.uri());
    }

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
                    new TypeReference<List<McpSchema.PromptArgument>>() {
                    });
        } catch (Exception e) {
            log.warn("Failed to parse prompt arguments JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
