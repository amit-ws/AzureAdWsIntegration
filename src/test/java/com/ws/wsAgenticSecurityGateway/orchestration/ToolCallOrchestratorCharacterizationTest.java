package com.ws.wsAgenticSecurityGateway.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentCapabilityFilterService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.orchestration.adapter.McpAdapter;
import com.ws.wsAgenticSecurityGateway.sts.model.ActChain;
import com.ws.wsAgenticSecurityGateway.sts.service.ActChainBuilder;
import com.ws.wsAgenticSecurityGateway.sts.service.HopTokenMinter;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationResult;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyContextBuilder;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSessionManager;
import com.ws.wsAgenticSecurityGateway.wsClient.service.McpClientService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests pinning the OBSERVABLE behavior of the MCP orchestration path
 * (public API: {@link ToolCallOrchestrator}). Written against the pre-refactor monolith and
 * kept green through the Stage-0 spine/adapter split — after the facade rewire they exercise
 * the full refactored chain (facade -> {@link HopOrchestrator} spine -> MCP adapter -> mocked
 * client) through the same public methods, which is the definition of "MCP stays green".
 *
 * <p>Spine dependencies are mocked; the MCP adapter is real (wrapping the mocked
 * McpClientService); the small Lombok DTOs are built for real. The MCP exchange is stubbed
 * minimally (empty transport context, a session id, null client info — all handled
 * gracefully) so the tests exercise the real control flow.
 */
class ToolCallOrchestratorCharacterizationTest {

    private final CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
    private final McpClientService mcpClientService = mock(McpClientService.class);
    private final McpAuditService auditService = mock(McpAuditService.class);
    private final InFlightRequestRegistry inFlight = mock(InFlightRequestRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpSessionManager mcpSessionManager = mock(McpSessionManager.class);
    private final AgentRegistryService agentRegistryService = mock(AgentRegistryService.class);
    private final AgentCapabilityFilterService capabilityFilterService = mock(AgentCapabilityFilterService.class);
    private final CedarPolicyEngine cedarPolicyEngine = mock(CedarPolicyEngine.class);
    private final PolicyContextBuilder policyContextBuilder = mock(PolicyContextBuilder.class);

    private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

    private ToolCallOrchestrator orchestrator;

    private static final String TOOL_PUBLIC = "github_create_issue";
    private static final String SERVER = "github";
    private static final String ORIGINAL = "create_issue";

    @BeforeEach
    void setUp() {
        McpAdapter adapter = new McpAdapter(mcpClientService);
        HopTokenMinter hopTokenMinter = mock(HopTokenMinter.class); // returns null -> mint skipped (open mode)
        ActChainBuilder actChainBuilder = mock(ActChainBuilder.class);
        when(actChainBuilder.fromTransportContext(any(), any())).thenReturn(new ActChain(List.of()));
        HopOrchestrator hopOrchestrator = new HopOrchestrator(registryService, auditService, inFlight,
                objectMapper, mcpSessionManager, agentRegistryService, capabilityFilterService,
                cedarPolicyEngine, policyContextBuilder, adapter, hopTokenMinter, actChainBuilder);
        orchestrator = new ToolCallOrchestrator(hopOrchestrator);

        when(exchange.transportContext()).thenReturn(McpTransportContext.EMPTY);
        when(exchange.sessionId()).thenReturn("test-session");
        when(exchange.getClientInfo()).thenReturn(null);

        // Default: no per-agent capability profile → capability filter is skipped.
        when(agentRegistryService.getAgentIdForSession(any())).thenReturn(null);

        // Default PDP: build a request and ALLOW.
        PolicyEvaluationRequest request = PolicyEvaluationRequest.builder().agentName("test-agent").build();
        when(policyContextBuilder.buildForToolCall(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(policyContextBuilder.buildForPromptGet(any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(policyContextBuilder.buildForResourceRead(any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(cedarPolicyEngine.evaluate(any()))
                .thenReturn(PolicyEvaluationResult.allow(Set.of("test-policy"), 1L));
    }

    private CapabilityDescriptor toolDescriptor() {
        return CapabilityDescriptor.builder()
                .publicName(TOOL_PUBLIC).originalName(ORIGINAL).serverConfigName(SERVER)
                .type(CapabilityDescriptor.CapabilityType.TOOL).build();
    }

    @Test
    @DisplayName("tool happy path → resolves name, forwards to client, returns non-error result")
    void toolCall_happyPath() {
        when(registryService.lookupByPublicName(TOOL_PUBLIC)).thenReturn(Optional.of(toolDescriptor()));
        when(mcpSessionManager.isConnected(SERVER)).thenReturn(true);
        when(mcpClientService.callTool(any(), eq(SERVER), eq(ORIGINAL), any(), any(), any()))
                .thenReturn(List.of());

        McpSchema.CallToolResult result =
                orchestrator.orchestrate(exchange, TOOL_PUBLIC, Map.of("title", "x"));

        assertThat(result.isError()).isFalse();
        verify(mcpClientService).callTool(any(), eq(SERVER), eq(ORIGINAL), any(), any(), any());
    }

    @Test
    @DisplayName("tool capability denied → error result, southbound call never made")
    void toolCall_capabilityDenied() {
        UUID agentId = UUID.randomUUID();
        when(agentRegistryService.getAgentIdForSession(any())).thenReturn(agentId);
        when(capabilityFilterService.isCapabilityAllowed(agentId, TOOL_PUBLIC, "TOOL")).thenReturn(false);

        McpSchema.CallToolResult result =
                orchestrator.orchestrate(exchange, TOOL_PUBLIC, Map.of("title", "x"));

        assertThat(result.isError()).isTrue();
        verify(mcpClientService, never()).callTool(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("tool not found in registry → error result, no southbound call")
    void toolCall_notFound() {
        when(registryService.lookupByPublicName(TOOL_PUBLIC)).thenReturn(Optional.empty());

        McpSchema.CallToolResult result =
                orchestrator.orchestrate(exchange, TOOL_PUBLIC, Map.of("title", "x"));

        assertThat(result.isError()).isTrue();
        verify(mcpClientService, never()).callTool(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("tool PDP denied → error result, no southbound call")
    void toolCall_pdpDenied() {
        when(registryService.lookupByPublicName(TOOL_PUBLIC)).thenReturn(Optional.of(toolDescriptor()));
        when(cedarPolicyEngine.evaluate(any()))
                .thenReturn(PolicyEvaluationResult.deny(Set.of("blocking-policy"), "not permitted", 1L));

        McpSchema.CallToolResult result =
                orchestrator.orchestrate(exchange, TOOL_PUBLIC, Map.of("title", "x"));

        assertThat(result.isError()).isTrue();
        verify(mcpClientService, never()).callTool(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("tool server not connected → error result, no southbound call")
    void toolCall_serverNotConnected() {
        when(registryService.lookupByPublicName(TOOL_PUBLIC)).thenReturn(Optional.of(toolDescriptor()));
        when(mcpSessionManager.isConnected(SERVER)).thenReturn(false);

        McpSchema.CallToolResult result =
                orchestrator.orchestrate(exchange, TOOL_PUBLIC, Map.of("title", "x"));

        assertThat(result.isError()).isTrue();
        verify(mcpClientService, never()).callTool(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("tool governance: deprovisioned agent → denied at orchestrator (defense-in-depth), no southbound call")
    void toolCall_governanceDeprovisioned() {
        when(registryService.lookupByPublicName(TOOL_PUBLIC)).thenReturn(Optional.of(toolDescriptor()));
        when(mcpSessionManager.isConnected(SERVER)).thenReturn(true);
        when(agentRegistryService.getAgentLifecycleStatusForSession(any())).thenReturn("DEPROVISIONED");

        McpSchema.CallToolResult result =
                orchestrator.orchestrate(exchange, TOOL_PUBLIC, Map.of("title", "x"));

        assertThat(result.isError()).isTrue();
        verify(mcpClientService, never()).callTool(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("prompt happy path → resolves name, delegates to client.getPrompt")
    void promptGet_happyPath() {
        CapabilityDescriptor d = CapabilityDescriptor.builder()
                .publicName("github_summary").originalName("summary").serverConfigName(SERVER)
                .type(CapabilityDescriptor.CapabilityType.PROMPT).build();
        when(registryService.lookupByPublicName("github_summary")).thenReturn(Optional.of(d));
        when(mcpSessionManager.isConnected(SERVER)).thenReturn(true);
        when(mcpClientService.getPrompt(eq(SERVER), eq("summary"), any()))
                .thenReturn(new McpSchema.GetPromptResult("test", List.of()));

        orchestrator.orchestrateGetPrompt(exchange, "github_summary", Map.of("k", "v"));

        verify(mcpClientService).getPrompt(eq(SERVER), eq("summary"), any());
    }

    @Test
    @DisplayName("resource happy path → resolves uri, delegates to client.readResource")
    void resourceRead_happyPath() {
        CapabilityDescriptor d = CapabilityDescriptor.builder()
                .publicName("github_readme").originalName("readme").serverConfigName(SERVER)
                .resourceUri("file://readme")
                .type(CapabilityDescriptor.CapabilityType.RESOURCE).build();
        when(registryService.lookupByPublicName("github_readme")).thenReturn(Optional.of(d));
        when(mcpSessionManager.isConnected(SERVER)).thenReturn(true);
        when(mcpClientService.readResource(any(), eq(SERVER), eq("file://readme"), any(), any()))
                .thenReturn(List.of());

        McpSchema.ReadResourceResult result =
                orchestrator.orchestrateReadResource(exchange, "github_readme", "file://readme");

        assertThat(result).isNotNull();
        verify(mcpClientService).readResource(any(), eq(SERVER), eq("file://readme"), any(), any());
    }
}
