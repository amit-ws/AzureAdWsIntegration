package com.ws.wsAgenticSecurityGateway.wsServer;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.orchestration.ToolCallOrchestrator;
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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
            }
            if (!toolSpecs.isEmpty()) {
                builder.tools(toolSpecs);
            }

            List<CapabilityDescriptor> promptDescriptors = registryService.getPromptDescriptors();
            List<McpStatelessServerFeatures.SyncPromptSpecification> promptSpecs = new ArrayList<>();
            for (CapabilityDescriptor descriptor : promptDescriptors) {
                promptSpecs.add(toStatelessPromptSpec(descriptor));
            }
            if (!promptSpecs.isEmpty()) {
                builder.prompts(promptSpecs);
            }

            List<CapabilityDescriptor> resourceDescriptors = registryService.getResourceDescriptors();
            List<McpStatelessServerFeatures.SyncResourceSpecification> resourceSpecs = new ArrayList<>();
            for (CapabilityDescriptor descriptor : resourceDescriptors) {
                resourceSpecs.add(toStatelessResourceSpec(descriptor));
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
