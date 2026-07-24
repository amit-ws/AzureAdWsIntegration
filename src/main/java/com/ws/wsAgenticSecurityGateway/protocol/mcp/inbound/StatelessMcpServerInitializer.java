package com.ws.wsAgenticSecurityGateway.protocol.mcp.inbound;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.event.CapabilityRegistryChangedEvent;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.inbound.ToolCallOrchestrator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Stateless MCP server (Delta 2) — serves the new spec model at {@code /mcp-stateless}: no
 * {@code initialize} handshake, no {@code Mcp-Session-Id}; identity/context ride per request on the
 * transport context ({@code _meta} + JWT).
 *
 * <p><b>Bridge design:</b> each stateless request is wrapped in a synthetic {@link McpSyncServerExchange}
 * (built from the per-request {@link McpTransportContext}) and dispatched through the exact same
 * {@link ToolCallOrchestrator} path as the session server — so governance, STS/OBO, act_chain and audit
 * all apply unchanged. The heavier request-context neutralization stays deferred to the A2A phase.
 */
@Component
@Slf4j
@Order(3)
@ConditionalOnProperty(name = "ws.gateway.transport", havingValue = "http", matchIfMissing = true)
public class StatelessMcpServerInitializer implements ApplicationRunner {

    private final HttpServletStatelessServerTransport transport;
    private final CapabilityRegistryService registryService;
    private final ToolCallOrchestrator orchestrator;
    private final StatelessIdentityService identityService;
    private final ObjectMapper objectMapper;

    private McpStatelessSyncServer server;

    // Live registration signatures (publicName/uri -> signature) so the runtime refresh can diff the SDK
    // server's registration against the registry — mirrors the session server, minus client notifications
    // (stateless clients re-query tools/list on every request, so there is nothing to notify).
    private final Object refreshLock = new Object();
    private final Map<String, String> registeredToolSignatures = new HashMap<>();
    private final Map<String, String> registeredPromptSignatures = new HashMap<>();
    private final Map<String, String> registeredResourceSignatures = new HashMap<>();

    public StatelessMcpServerInitializer(HttpServletStatelessServerTransport transport,
                                         CapabilityRegistryService registryService,
                                         ToolCallOrchestrator orchestrator,
                                         StatelessIdentityService identityService,
                                         ObjectMapper objectMapper) {
        this.transport = transport;
        this.registryService = registryService;
        this.orchestrator = orchestrator;
        this.identityService = identityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .resources(true, true)
                    .prompts(true)
                    .build();

            var builder = McpServer.sync(transport)
                    .serverInfo("ws-mcp-gateway-stateless", "1.0.0")
                    .capabilities(capabilities);

            List<CapabilityDescriptor> toolDescriptors = registryService.getToolDescriptors();
            List<McpStatelessServerFeatures.SyncToolSpecification> toolSpecs = new ArrayList<>();
            for (CapabilityDescriptor descriptor : toolDescriptors) {
                toolSpecs.add(toStatelessToolSpec(descriptor));
                registeredToolSignatures.put(descriptor.getPublicName(), toolSignature(descriptor));
            }
            if (!toolSpecs.isEmpty()) {
                builder.tools(toolSpecs);
            }

            List<CapabilityDescriptor> promptDescriptors = registryService.getPromptDescriptors();
            List<McpStatelessServerFeatures.SyncPromptSpecification> promptSpecs = new ArrayList<>();
            for (CapabilityDescriptor descriptor : promptDescriptors) {
                promptSpecs.add(toStatelessPromptSpec(descriptor));
                registeredPromptSignatures.put(descriptor.getPublicName(), promptSignature(descriptor));
            }
            if (!promptSpecs.isEmpty()) {
                builder.prompts(promptSpecs);
            }

            List<CapabilityDescriptor> resourceDescriptors = registryService.getResourceDescriptors();
            List<McpStatelessServerFeatures.SyncResourceSpecification> resourceSpecs = new ArrayList<>();
            for (CapabilityDescriptor descriptor : resourceDescriptors) {
                resourceSpecs.add(toStatelessResourceSpec(descriptor));
                registeredResourceSignatures.put(descriptor.getResourceUri(), resourceSignature(descriptor));
            }
            if (!resourceSpecs.isEmpty()) {
                builder.resources(resourceSpecs);
            }

            server = builder.build();
 log.info("WS MCP STATELESS SERVER STARTED (HTTP MODE) — endpoint /mcp-stateless, {} tools, {} prompts, {} resources",
                    toolSpecs.size(), promptSpecs.size(), resourceSpecs.size());
        } catch (Exception e) {
 log.error("WS MCP STATELESS SERVER INITIALIZATION FAILED: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (server != null) {
            try {
                server.close();
            } catch (Exception e) {
                log.debug("Stateless server shutdown: {}", e.getMessage());
            }
        }
    }

    /**
     * Keep the stateless server's registered capabilities in sync with the registry when downstream servers
     * are (dis)connected AFTER startup — the stateless counterpart of the session server's refresh. Without
     * this, a server configured after boot (e.g. a fresh schema where the admin adds a server post-startup)
     * would never appear in {@code tools/list}. No client notifications: stateless clients re-query on each
     * request.
     */
    @EventListener
    public void onCapabilityRegistryChanged(CapabilityRegistryChangedEvent event) {
        reconcileCapabilities(event.getReason(), event.getServerConfigName());
    }

    private void reconcileCapabilities(String reason, String serverConfigName) {
        synchronized (refreshLock) {
            if (server == null) {
                log.debug("Skipping stateless capability refresh (reason={}, server={}): not initialized yet",
                        reason, serverConfigName);
                return;
            }
            int tAdd = 0, tUpd = 0, tRem = 0, pAdd = 0, pUpd = 0, pRem = 0, rAdd = 0, rUpd = 0, rRem = 0;

            Map<String, CapabilityDescriptor> desiredTools = new LinkedHashMap<>();
            for (CapabilityDescriptor d : registryService.getToolDescriptors()) desiredTools.put(d.getPublicName(), d);
            Map<String, CapabilityDescriptor> desiredPrompts = new LinkedHashMap<>();
            for (CapabilityDescriptor d : registryService.getPromptDescriptors()) desiredPrompts.put(d.getPublicName(), d);
            Map<String, CapabilityDescriptor> desiredResources = new LinkedHashMap<>();
            for (CapabilityDescriptor d : registryService.getResourceDescriptors()) desiredResources.put(d.getResourceUri(), d);

            // Tools (keyed by public name)
            for (String name : new ArrayList<>(registeredToolSignatures.keySet())) {
                if (!desiredTools.containsKey(name) && safeRemoveTool(name)) {
                    registeredToolSignatures.remove(name); tRem++;
                }
            }
            for (CapabilityDescriptor d : desiredTools.values()) {
                String name = d.getPublicName(), sig = toolSignature(d), cur = registeredToolSignatures.get(name);
                if (cur == null) {
                    if (safeAddTool(d)) { registeredToolSignatures.put(name, sig); tAdd++; }
                } else if (!cur.equals(sig) && safeRemoveTool(name)) {
                    registeredToolSignatures.remove(name);
                    if (safeAddTool(d)) { registeredToolSignatures.put(name, sig); tUpd++; }
                }
            }

            // Prompts (keyed by public name)
            for (String name : new ArrayList<>(registeredPromptSignatures.keySet())) {
                if (!desiredPrompts.containsKey(name) && safeRemovePrompt(name)) {
                    registeredPromptSignatures.remove(name); pRem++;
                }
            }
            for (CapabilityDescriptor d : desiredPrompts.values()) {
                String name = d.getPublicName(), sig = promptSignature(d), cur = registeredPromptSignatures.get(name);
                if (cur == null) {
                    if (safeAddPrompt(d)) { registeredPromptSignatures.put(name, sig); pAdd++; }
                } else if (!cur.equals(sig) && safeRemovePrompt(name)) {
                    registeredPromptSignatures.remove(name);
                    if (safeAddPrompt(d)) { registeredPromptSignatures.put(name, sig); pUpd++; }
                }
            }

            // Resources (keyed by uri)
            for (String uri : new ArrayList<>(registeredResourceSignatures.keySet())) {
                if (!desiredResources.containsKey(uri) && safeRemoveResource(uri)) {
                    registeredResourceSignatures.remove(uri); rRem++;
                }
            }
            for (CapabilityDescriptor d : desiredResources.values()) {
                String uri = d.getResourceUri(), sig = resourceSignature(d), cur = registeredResourceSignatures.get(uri);
                if (cur == null) {
                    if (safeAddResource(d)) { registeredResourceSignatures.put(uri, sig); rAdd++; }
                } else if (!cur.equals(sig) && safeRemoveResource(uri)) {
                    registeredResourceSignatures.remove(uri);
                    if (safeAddResource(d)) { registeredResourceSignatures.put(uri, sig); rUpd++; }
                }
            }

            if (tAdd + tUpd + tRem + pAdd + pUpd + pRem + rAdd + rUpd + rRem > 0) {
 log.info("Stateless MCP capability refresh [{}:{}] tools(+{},~{},-{}), prompts(+{},~{},-{}), resources(+{},~{},-{})",
                        reason, serverConfigName, tAdd, tUpd, tRem, pAdd, pUpd, pRem, rAdd, rUpd, rRem);
            }
        }
    }

    private boolean safeAddTool(CapabilityDescriptor d) {
        try { server.addTool(toStatelessToolSpec(d)); return true; }
        catch (Exception e) { log.error("stateless addTool '{}' failed: {}", d.getPublicName(), e.getMessage()); return false; }
    }
    private boolean safeRemoveTool(String name) {
        try { server.removeTool(name); return true; }
        catch (Exception e) { log.error("stateless removeTool '{}' failed: {}", name, e.getMessage()); return false; }
    }
    private boolean safeAddPrompt(CapabilityDescriptor d) {
        try { server.addPrompt(toStatelessPromptSpec(d)); return true; }
        catch (Exception e) { log.error("stateless addPrompt '{}' failed: {}", d.getPublicName(), e.getMessage()); return false; }
    }
    private boolean safeRemovePrompt(String name) {
        try { server.removePrompt(name); return true; }
        catch (Exception e) { log.error("stateless removePrompt '{}' failed: {}", name, e.getMessage()); return false; }
    }
    private boolean safeAddResource(CapabilityDescriptor d) {
        try { server.addResource(toStatelessResourceSpec(d)); return true; }
        catch (Exception e) { log.error("stateless addResource '{}' failed: {}", d.getResourceUri(), e.getMessage()); return false; }
    }
    private boolean safeRemoveResource(String uri) {
        try { server.removeResource(uri); return true; }
        catch (Exception e) { log.error("stateless removeResource '{}' failed: {}", uri, e.getMessage()); return false; }
    }

    private static String toolSignature(CapabilityDescriptor d) {
        return String.join("", nz(d.getServerConfigName()), nz(d.getOriginalName()), nz(d.getDescription()), nz(d.getInputSchema()));
    }
    private static String promptSignature(CapabilityDescriptor d) {
        return String.join("", nz(d.getServerConfigName()), nz(d.getOriginalName()), nz(d.getDescription()), nz(d.getArguments()));
    }
    private static String resourceSignature(CapabilityDescriptor d) {
        return String.join("", nz(d.getServerConfigName()), nz(d.getResourceUri()), nz(d.getDescription()), nz(d.getMimeType()));
    }
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private McpStatelessServerFeatures.SyncToolSpecification toStatelessToolSpec(CapabilityDescriptor descriptor) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(descriptor.getPublicName())
                .description(descriptor.getDescription())
                .inputSchema(descriptor.getInputSchema())
                .build();
        return new McpStatelessServerFeatures.SyncToolSpecification(tool, this::handleStatelessToolCall);
    }

    private McpStatelessServerFeatures.SyncPromptSpecification toStatelessPromptSpec(CapabilityDescriptor descriptor) {
        McpSchema.Prompt prompt = new McpSchema.Prompt(
                descriptor.getPublicName(),
                descriptor.getDescription(),
                parsePromptArguments(descriptor.getArguments()));
        return new McpStatelessServerFeatures.SyncPromptSpecification(prompt, this::handleStatelessGetPrompt);
    }

    private McpStatelessServerFeatures.SyncResourceSpecification toStatelessResourceSpec(CapabilityDescriptor descriptor) {
        McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri(descriptor.getResourceUri())
                .name(descriptor.getPublicName())
                .description(descriptor.getDescription())
                .mimeType(descriptor.getMimeType())
                .build();
        return new McpStatelessServerFeatures.SyncResourceSpecification(resource, this::handleStatelessReadResource);
    }

    private McpSchema.CallToolResult handleStatelessToolCall(McpTransportContext ctx,
                                                             McpSchema.CallToolRequest request) {
        try {
            return withStatelessIdentity(ctx,
                    exchange -> orchestrator.orchestrate(exchange, request.name(), request.arguments()));
        } catch (AgentRegistryService.AgentBlockedException blocked) {
            return new McpSchema.CallToolResult(blocked.getMessage(), true);
        }
    }

    private McpSchema.GetPromptResult handleStatelessGetPrompt(McpTransportContext ctx,
                                                               McpSchema.GetPromptRequest request) {
        return withStatelessIdentity(ctx,
                exchange -> orchestrator.orchestrateGetPrompt(exchange, request.name(), request.arguments()));
    }

    private McpSchema.ReadResourceResult handleStatelessReadResource(McpTransportContext ctx,
                                                                     McpSchema.ReadResourceRequest request) {
        return withStatelessIdentity(ctx,
                exchange -> orchestrator.orchestrateReadResource(
                        exchange, resolvePublicNameByUri(request.uri()), request.uri()));
    }

    /**
     * Bootstrap per-request identity from the JWT, run the action against a synthetic exchange (so the full
     * governed flow applies), then drop the per-request identity. An {@code AgentBlockedException} propagates
     * to the caller: tools catch it → error result; prompts/resources let it surface as a JSON-RPC error,
     * matching session-mode behavior.
     */
    private <T> T withStatelessIdentity(McpTransportContext ctx, Function<McpSyncServerExchange, T> action) {
        String sessionId = "stateless-" + UUID.randomUUID();
        try {
            identityService.bootstrap(ctx, sessionId);
            return action.apply(syntheticExchange(ctx, sessionId));
        } finally {
            identityService.cleanup(sessionId); // no long-lived state — drop the per-request identity
        }
    }

    /**
     * Wrap a stateless request's transport context in a synthetic {@link McpSyncServerExchange} so it flows
     * through the exact session-based governed path unchanged. The orchestrator only reads
     * {@code transportContext()}, {@code sessionId()} and {@code getClientInfo()} — never the session — so a
     * null session/clientInfo is safe; the identity rides on the transport context ({@code _meta} + JWT).
     */
    private McpSyncServerExchange syntheticExchange(McpTransportContext ctx, String sessionId) {
        McpAsyncServerExchange async = new McpAsyncServerExchange(sessionId, null, null, null, ctx);
        return new McpSyncServerExchange(async);
    }

    private List<McpSchema.PromptArgument> parsePromptArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<List<McpSchema.PromptArgument>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse stateless prompt arguments: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String resolvePublicNameByUri(String uri) {
        for (CapabilityDescriptor descriptor : registryService.getResourceDescriptors()) {
            if (uri != null && uri.equals(descriptor.getResourceUri())) {
                return descriptor.getPublicName();
            }
        }
        return uri;
    }
}
