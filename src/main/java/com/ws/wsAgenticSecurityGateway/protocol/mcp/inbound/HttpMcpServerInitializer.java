package com.ws.wsAgenticSecurityGateway.protocol.mcp.inbound;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.event.CapabilityProfileChangedEvent;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.event.CapabilityRegistryChangedEvent;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.inbound.ToolCallOrchestrator;
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
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@Order(2)
@ConditionalOnProperty(name = "ws.gateway.transport", havingValue = "http", matchIfMissing = true)
public class HttpMcpServerInitializer implements ApplicationRunner {

    private final HttpServletStreamableServerTransportProvider transportProvider;
    private final CapabilityRegistryService registryService;
    private final ToolCallOrchestrator orchestrator;
    private final AgentRegistryService agentRegistryService;
    private final GatewayAuditService auditService;
    private final GatewayAgentSessionRepository agentSessionRepository;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Boolean> checkedSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> registeredToolSignatures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> registeredPromptSignatures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> registeredResourceSignatures = new ConcurrentHashMap<>();
    private final Object capabilityRefreshLock = new Object();

    private McpSyncServer server;

    public HttpMcpServerInitializer(HttpServletStreamableServerTransportProvider transportProvider,
                                     CapabilityRegistryService registryService,
                                     ToolCallOrchestrator orchestrator,
                                     AgentRegistryService agentRegistryService,
                                     GatewayAuditService auditService,
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
 log.info("WS MCP SERVER INITIALIZATION (HTTP MODE)");

            McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .resources(true, true)
                    .prompts(true)
                    .build();

            var serverBuilder = McpServer.sync(transportProvider)
                    .serverInfo("ws-mcp-gateway", "1.0.0")
                    .capabilities(capabilities);

            List<CapabilityDescriptor> toolDescriptors = registryService.getToolDescriptors();
            List<McpServerFeatures.SyncToolSpecification> toolSpecs = new ArrayList<>();

            for (CapabilityDescriptor descriptor : toolDescriptors) {
                toolSpecs.add(toToolSpec(descriptor));
 log.info("Registered tool: {} (from '{}')",
                        descriptor.getPublicName(), descriptor.getServerConfigName());
            }
            if (!toolSpecs.isEmpty()) {
                serverBuilder.tools(toolSpecs);
            }

            List<CapabilityDescriptor> promptDescriptors = registryService.getPromptDescriptors();
            if (!promptDescriptors.isEmpty()) {
                List<McpServerFeatures.SyncPromptSpecification> promptSpecs = new ArrayList<>();

                for (CapabilityDescriptor descriptor : promptDescriptors) {
                    promptSpecs.add(toPromptSpec(descriptor));
 log.info("Registered prompt: {} (from '{}')",
                            descriptor.getPublicName(), descriptor.getServerConfigName());
                }
                serverBuilder.prompts(promptSpecs);
            }

            List<CapabilityDescriptor> resourceDescriptors = registryService.getResourceDescriptors();
            if (!resourceDescriptors.isEmpty()) {
                List<McpServerFeatures.SyncResourceSpecification> resourceSpecs = new ArrayList<>();

                for (CapabilityDescriptor descriptor : resourceDescriptors) {
                    resourceSpecs.add(toResourceSpec(descriptor));
 log.info("Registered resource: {} → {} (from '{}')",
                            descriptor.getPublicName(), descriptor.getResourceUri(),
                            descriptor.getServerConfigName());
                }
                serverBuilder.resources(resourceSpecs);
            }

            server = serverBuilder.build();
            primeRegisteredSignatures(toolDescriptors, promptDescriptors, resourceDescriptors);

 log.info("WS MCP SERVER STARTED (HTTP MODE)");
            log.info("Server: ws-mcp-gateway v1.0.0");
            log.info("Transport: HTTP Streamable at /mcp");
            log.info("Tools:     {} (from {} enterprise servers)",
                    toolDescriptors.size(), registryService.getRegisteredServerNames().size());
            log.info("Prompts:   {}", promptDescriptors.size());
            log.info("Resources: {}", resourceDescriptors.size());
            log.info("Ready for agent connections via HTTP");

        } catch (Exception e) {
 log.error("WS MCP SERVER (HTTP) INITIALIZATION FAILED");
            log.error("Error: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
 log.info("WS MCP Server (HTTP) shutting down...");
        if (server != null) {
            try {
                server.closeGracefully();
 log.info("HTTP server shutdown complete");
            } catch (Exception e) {
                log.error("Error during HTTP server shutdown: {}", e.getMessage());
            }
        }
    }

    @EventListener
    public void onCapabilityRegistryChanged(CapabilityRegistryChangedEvent event) {
        refreshCapabilitiesFromRegistry(event.getReason(), event.getServerConfigName());
    }

    @EventListener
    public void onCapabilityProfileChanged(CapabilityProfileChangedEvent event) {
        if (server == null) {
            log.debug("MCP server not initialized yet — skipping profile change notification");
            return;
        }

 log.info("Capability profile change [{}] profile='{}' affectedAgents={} — broadcasting notifications",
                event.getReason(), event.getProfileName(), event.getAffectedAgentNames());

        server.notifyToolsListChanged();
        server.notifyPromptsListChanged();
        server.notifyResourcesListChanged();

        List<String> notifiedAgents = agentRegistryService.getConnectedSessions().stream()
                .filter(s -> s.getAgent() != null)
                .map(s -> s.getAgent().getAgentName())
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        auditService.auditProfileNotificationBroadcast(
                event.getReason(), event.getProfileName(), event.getProfileId(),
                event.getAffectedAgentNames(), notifiedAgents);
    }

    private void ensureAgentRegistered(McpSyncServerExchange exchange) {
        String sessionId = exchange.sessionId();
        if (sessionId == null || checkedSessions.containsKey(sessionId)) {
            return;
        }
        if (checkedSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            return;
        }

        try {
            if (agentSessionRepository.findBySessionId(sessionId).isPresent()) {
                log.debug("Session {} already registered by audit filter", sessionId);
                return;
            }

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

    private void primeRegisteredSignatures(List<CapabilityDescriptor> toolDescriptors,
                                           List<CapabilityDescriptor> promptDescriptors,
                                           List<CapabilityDescriptor> resourceDescriptors) {
        registeredToolSignatures.clear();
        for (CapabilityDescriptor descriptor : toolDescriptors) {
            registeredToolSignatures.put(descriptor.getPublicName(), signatureForTool(descriptor));
        }

        registeredPromptSignatures.clear();
        for (CapabilityDescriptor descriptor : promptDescriptors) {
            registeredPromptSignatures.put(descriptor.getPublicName(), signatureForPrompt(descriptor));
        }

        registeredResourceSignatures.clear();
        for (CapabilityDescriptor descriptor : resourceDescriptors) {
            if (descriptor.getResourceUri() != null) {
                registeredResourceSignatures.put(descriptor.getResourceUri(), signatureForResource(descriptor));
            }
        }
    }

    private void refreshCapabilitiesFromRegistry(String reason, String serverConfigName) {
        synchronized (capabilityRefreshLock) {
            if (server == null) {
                log.debug("Skipping capability refresh (reason={}, server={}): MCP server not initialized yet",
                        reason, serverConfigName);
                return;
            }

            int toolAdded = 0, toolUpdated = 0, toolRemoved = 0;
            int promptAdded = 0, promptUpdated = 0, promptRemoved = 0;
            int resourceAdded = 0, resourceUpdated = 0, resourceRemoved = 0;

            Map<String, CapabilityDescriptor> desiredTools = asToolMap(registryService.getToolDescriptors());
            Map<String, CapabilityDescriptor> desiredPrompts = asPromptMap(registryService.getPromptDescriptors());
            Map<String, CapabilityDescriptor> desiredResources = asResourceMap(registryService.getResourceDescriptors());

            for (String existingName : new ArrayList<>(registeredToolSignatures.keySet())) {
                if (!desiredTools.containsKey(existingName)) {
                    if (safeRemoveTool(existingName)) {
                        registeredToolSignatures.remove(existingName);
                        toolRemoved++;
                    }
                }
            }
            for (CapabilityDescriptor descriptor : desiredTools.values()) {
                String name = descriptor.getPublicName();
                String desiredSignature = signatureForTool(descriptor);
                String existingSignature = registeredToolSignatures.get(name);
                if (existingSignature == null) {
                    if (safeAddTool(descriptor)) {
                        registeredToolSignatures.put(name, desiredSignature);
                        toolAdded++;
                    }
                } else if (!existingSignature.equals(desiredSignature)) {
                    if (safeRemoveTool(name)) {
                        registeredToolSignatures.remove(name);
                        if (safeAddTool(descriptor)) {
                            registeredToolSignatures.put(name, desiredSignature);
                            toolUpdated++;
                        }
                    }
                }
            }

            for (String existingName : new ArrayList<>(registeredPromptSignatures.keySet())) {
                if (!desiredPrompts.containsKey(existingName)) {
                    if (safeRemovePrompt(existingName)) {
                        registeredPromptSignatures.remove(existingName);
                        promptRemoved++;
                    }
                }
            }
            for (CapabilityDescriptor descriptor : desiredPrompts.values()) {
                String name = descriptor.getPublicName();
                String desiredSignature = signatureForPrompt(descriptor);
                String existingSignature = registeredPromptSignatures.get(name);
                if (existingSignature == null) {
                    if (safeAddPrompt(descriptor)) {
                        registeredPromptSignatures.put(name, desiredSignature);
                        promptAdded++;
                    }
                } else if (!existingSignature.equals(desiredSignature)) {
                    if (safeRemovePrompt(name)) {
                        registeredPromptSignatures.remove(name);
                        if (safeAddPrompt(descriptor)) {
                            registeredPromptSignatures.put(name, desiredSignature);
                            promptUpdated++;
                        }
                    }
                }
            }

            for (String existingUri : new ArrayList<>(registeredResourceSignatures.keySet())) {
                if (!desiredResources.containsKey(existingUri)) {
                    if (safeRemoveResource(existingUri)) {
                        registeredResourceSignatures.remove(existingUri);
                        resourceRemoved++;
                    }
                }
            }
            for (CapabilityDescriptor descriptor : desiredResources.values()) {
                String uri = descriptor.getResourceUri();
                String desiredSignature = signatureForResource(descriptor);
                String existingSignature = registeredResourceSignatures.get(uri);
                if (existingSignature == null) {
                    if (safeAddResource(descriptor)) {
                        registeredResourceSignatures.put(uri, desiredSignature);
                        resourceAdded++;
                    }
                } else if (!existingSignature.equals(desiredSignature)) {
                    if (safeRemoveResource(uri)) {
                        registeredResourceSignatures.remove(uri);
                        if (safeAddResource(descriptor)) {
                            registeredResourceSignatures.put(uri, desiredSignature);
                            resourceUpdated++;
                        }
                    }
                }
            }

            if (toolAdded + toolUpdated + toolRemoved > 0) {
                server.notifyToolsListChanged();
            }
            if (promptAdded + promptUpdated + promptRemoved > 0) {
                server.notifyPromptsListChanged();
            }
            if (resourceAdded + resourceUpdated + resourceRemoved > 0) {
                server.notifyResourcesListChanged();
            }

            boolean toolsNotified = toolAdded + toolUpdated + toolRemoved > 0;
            boolean promptsNotified = promptAdded + promptUpdated + promptRemoved > 0;
            boolean resourcesNotified = resourceAdded + resourceUpdated + resourceRemoved > 0;

            if (toolsNotified || promptsNotified || resourcesNotified) {
                log.info(
 "MCP capability refresh [{}:{}] tools(+{},~{},-{}), prompts(+{},~{},-{}), resources(+{},~{},-{})",
                        reason, serverConfigName,
                        toolAdded, toolUpdated, toolRemoved,
                        promptAdded, promptUpdated, promptRemoved,
                        resourceAdded, resourceUpdated, resourceRemoved);

                List<String> notifiedAgents = agentRegistryService.getConnectedSessions().stream()
                        .filter(s -> s.getAgent() != null)
                        .map(s -> s.getAgent().getAgentName())
                        .distinct()
                        .collect(java.util.stream.Collectors.toList());

                auditService.auditNotificationBroadcast(reason, serverConfigName,
                        toolAdded, toolUpdated, toolRemoved,
                        promptAdded, promptUpdated, promptRemoved,
                        resourceAdded, resourceUpdated, resourceRemoved,
                        toolsNotified, promptsNotified, resourcesNotified,
                        notifiedAgents);
            } else {
                log.debug("MCP capability refresh [{}:{}] - no effective changes", reason, serverConfigName);
            }
        }
    }

    private Map<String, CapabilityDescriptor> asToolMap(List<CapabilityDescriptor> descriptors) {
        Map<String, CapabilityDescriptor> out = new LinkedHashMap<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            out.put(descriptor.getPublicName(), descriptor);
        }
        return out;
    }

    private Map<String, CapabilityDescriptor> asPromptMap(List<CapabilityDescriptor> descriptors) {
        Map<String, CapabilityDescriptor> out = new LinkedHashMap<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            out.put(descriptor.getPublicName(), descriptor);
        }
        return out;
    }

    private Map<String, CapabilityDescriptor> asResourceMap(List<CapabilityDescriptor> descriptors) {
        Map<String, CapabilityDescriptor> out = new LinkedHashMap<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            if (descriptor.getResourceUri() != null) {
                out.put(descriptor.getResourceUri(), descriptor);
            }
        }
        return out;
    }

    private String signatureForTool(CapabilityDescriptor descriptor) {
        return descriptor.getPublicName() + "|"
                + Objects.toString(descriptor.getDescription(), "") + "|"
                + Objects.toString(descriptor.getInputSchema(), "");
    }

    private String signatureForPrompt(CapabilityDescriptor descriptor) {
        return descriptor.getPublicName() + "|"
                + Objects.toString(descriptor.getDescription(), "") + "|"
                + Objects.toString(descriptor.getArguments(), "");
    }

    private String signatureForResource(CapabilityDescriptor descriptor) {
        return Objects.toString(descriptor.getResourceUri(), "") + "|"
                + descriptor.getPublicName() + "|"
                + Objects.toString(descriptor.getDescription(), "") + "|"
                + Objects.toString(descriptor.getMimeType(), "");
    }

    private McpServerFeatures.SyncToolSpecification toToolSpec(CapabilityDescriptor descriptor) {
        McpSchema.Tool toolDef = McpSchema.Tool.builder()
                .name(descriptor.getPublicName())
                .description(descriptor.getDescription())
                .inputSchema(descriptor.getInputSchema())
                .build();
        return new McpServerFeatures.SyncToolSpecification(toolDef, null, this::handleToolCall);
    }

    private McpServerFeatures.SyncPromptSpecification toPromptSpec(CapabilityDescriptor descriptor) {
        List<McpSchema.PromptArgument> promptArgs = parsePromptArguments(descriptor.getArguments());
        McpSchema.Prompt promptDef = new McpSchema.Prompt(
                descriptor.getPublicName(),
                descriptor.getDescription(),
                promptArgs);
        return new McpServerFeatures.SyncPromptSpecification(promptDef, this::handleGetPrompt);
    }

    private McpServerFeatures.SyncResourceSpecification toResourceSpec(CapabilityDescriptor descriptor) {
        McpSchema.Resource resourceDef = McpSchema.Resource.builder()
                .uri(descriptor.getResourceUri())
                .name(descriptor.getPublicName())
                .description(descriptor.getDescription())
                .mimeType(descriptor.getMimeType())
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resourceDef, this::handleReadResource);
    }

    private boolean safeAddTool(CapabilityDescriptor descriptor) {
        try {
            server.addTool(toToolSpec(descriptor));
            return true;
        } catch (Exception e) {
            log.error("Failed to add tool '{}' during runtime refresh: {}",
                    descriptor.getPublicName(), e.getMessage());
            return false;
        }
    }

    private boolean safeRemoveTool(String toolName) {
        try {
            server.removeTool(toolName);
            return true;
        } catch (Exception e) {
            log.error("Failed to remove tool '{}' during runtime refresh: {}", toolName, e.getMessage());
            return false;
        }
    }

    private boolean safeAddPrompt(CapabilityDescriptor descriptor) {
        try {
            server.addPrompt(toPromptSpec(descriptor));
            return true;
        } catch (Exception e) {
            log.error("Failed to add prompt '{}' during runtime refresh: {}",
                    descriptor.getPublicName(), e.getMessage());
            return false;
        }
    }

    private boolean safeRemovePrompt(String promptName) {
        try {
            server.removePrompt(promptName);
            return true;
        } catch (Exception e) {
            log.error("Failed to remove prompt '{}' during runtime refresh: {}", promptName, e.getMessage());
            return false;
        }
    }

    private boolean safeAddResource(CapabilityDescriptor descriptor) {
        try {
            server.addResource(toResourceSpec(descriptor));
            return true;
        } catch (Exception e) {
            log.error("Failed to add resource '{}' during runtime refresh: {}",
                    descriptor.getResourceUri(), e.getMessage());
            return false;
        }
    }

    private boolean safeRemoveResource(String resourceUri) {
        try {
            server.removeResource(resourceUri);
            return true;
        } catch (Exception e) {
            log.error("Failed to remove resource '{}' during runtime refresh: {}", resourceUri, e.getMessage());
            return false;
        }
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
                    new TypeReference<List<McpSchema.PromptArgument>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse prompt arguments JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
