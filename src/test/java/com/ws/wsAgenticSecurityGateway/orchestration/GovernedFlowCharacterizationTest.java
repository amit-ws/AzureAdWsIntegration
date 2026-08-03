package com.ws.wsAgenticSecurityGateway.orchestration;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.inbound.ToolCallOrchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentCapabilityFilterService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.orchestration.adapter.McpAdapter;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationResult;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyContextBuilder;
import com.ws.wsAgenticSecurityGateway.sts.model.ActChain;
import com.ws.wsAgenticSecurityGateway.sts.service.ActChainBuilder;
import com.ws.wsAgenticSecurityGateway.sts.service.HopTokenMinter;
import com.ws.wsAgenticSecurityGateway.sts.service.StsMintException;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config.McpSessionManager;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.service.McpClientService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for the GOVERNED request flow — the safety net for the structural refactor
 * (see {@code docs/structural-refactor-plan.md}).
 *
 * <p>These pin behaviour that later refactor bands are most likely to disturb and that
 * {@code ToolCallOrchestratorCharacterizationTest} does not already cover:
 * <ul>
 *   <li><b>Fail-closed STS minting</b> — if the delegation token cannot be minted, the hop must be
 *       DENIED and the southbound call must never happen. A refactor that accidentally turned this
 *       fail-open would be a silent security regression, so it is pinned for all three capability types.</li>
 *   <li><b>Prompt and resource denial paths</b> — the existing suite only covers their happy paths,
 *       yet Bands 3–4 move and re-type exactly these handlers.</li>
 *   <li><b>The mint is actually invoked</b> for a governed hop (Band 4 re-parameterises ScopeDeriver
 *       and the act_chain plumbing).</li>
 * </ul>
 *
 * <p>Note the asymmetry being pinned: tool denials RETURN an error {@code CallToolResult}, while prompt
 * and resource denials THROW. That is existing behaviour, and characterizing it means a refactor cannot
 * quietly change it.
 *
 * <p>Spine dependencies are mocked; the MCP adapter is real (wrapping the mocked client), so these
 * exercise the real control flow through the public facade.
 */
class GovernedFlowCharacterizationTest {

    private final CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
    private final McpClientService mcpClientService = mock(McpClientService.class);
    private final GatewayAuditService auditService = mock(GatewayAuditService.class);
    private final InFlightRequestRegistry inFlight = mock(InFlightRequestRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpSessionManager mcpSessionManager = mock(McpSessionManager.class);
    private final AgentRegistryService agentRegistryService = mock(AgentRegistryService.class);
    private final AgentCapabilityFilterService capabilityFilterService = mock(AgentCapabilityFilterService.class);
    private final CedarPolicyEngine cedarPolicyEngine = mock(CedarPolicyEngine.class);
    private final PolicyContextBuilder policyContextBuilder = mock(PolicyContextBuilder.class);
    private final HopTokenMinter hopTokenMinter = mock(HopTokenMinter.class);

    private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

    private ToolCallOrchestrator orchestrator;

    private static final String SERVER = "github";
    private static final String TOOL_PUBLIC = "github_create_issue";
    private static final String TOOL_ORIGINAL = "create_issue";
    private static final String PROMPT_PUBLIC = "github_summary";
    private static final String PROMPT_ORIGINAL = "summary";
    private static final String RESOURCE_PUBLIC = "github_readme";
    private static final String RESOURCE_URI = "file://readme";

    @BeforeEach
    void setUp() {
        McpAdapter adapter = new McpAdapter(mcpClientService, mcpSessionManager);
        ActChainBuilder actChainBuilder = mock(ActChainBuilder.class);
        when(actChainBuilder.fromTransportContext(any(), any())).thenReturn(new ActChain(List.of()));

        HopOrchestrator hopOrchestrator = new HopOrchestrator(registryService, auditService, inFlight,
                objectMapper, agentRegistryService, capabilityFilterService,
                cedarPolicyEngine, policyContextBuilder, adapter, hopTokenMinter, actChainBuilder);
        orchestrator = new ToolCallOrchestrator(hopOrchestrator);

        when(exchange.transportContext()).thenReturn(McpTransportContext.EMPTY);
        when(exchange.sessionId()).thenReturn("test-session");
        when(exchange.getClientInfo()).thenReturn(null);

        // Defaults: no capability profile, target connected, PDP allows.
        when(agentRegistryService.getAgentIdForSession(any())).thenReturn(null);
        when(mcpSessionManager.isConnected(SERVER)).thenReturn(true);

        PolicyEvaluationRequest request = PolicyEvaluationRequest.builder().agentName("test-agent").build();
        when(policyContextBuilder.buildForToolCall(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(policyContextBuilder.buildForPromptGet(any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(policyContextBuilder.buildForResourceRead(any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(cedarPolicyEngine.evaluate(any(), any()))
                .thenReturn(PolicyEvaluationResult.allow(Set.of("test-policy"), 1L));
    }

    private void givenTool() {
        when(registryService.lookupByPublicName(TOOL_PUBLIC)).thenReturn(Optional.of(
                CapabilityDescriptor.builder()
                        .publicName(TOOL_PUBLIC).originalName(TOOL_ORIGINAL).serverConfigName(SERVER)
                        .type(CapabilityDescriptor.CapabilityType.TOOL).build()));
    }

    private void givenPrompt() {
        when(registryService.lookupByPublicName(PROMPT_PUBLIC)).thenReturn(Optional.of(
                CapabilityDescriptor.builder()
                        .publicName(PROMPT_PUBLIC).originalName(PROMPT_ORIGINAL).serverConfigName(SERVER)
                        .type(CapabilityDescriptor.CapabilityType.PROMPT).build()));
    }

    private void givenResource() {
        when(registryService.lookupByPublicName(RESOURCE_PUBLIC)).thenReturn(Optional.of(
                CapabilityDescriptor.builder()
                        .publicName(RESOURCE_PUBLIC).originalName("readme").serverConfigName(SERVER)
                        .resourceUri(RESOURCE_URI)
                        .type(CapabilityDescriptor.CapabilityType.RESOURCE).build()));
    }

    // ── Fail-closed STS minting (security-critical) ────────────────────────────────────────

    @Test
    @DisplayName("tool: STS mint failure → FAIL-CLOSED (error result, southbound call never made)")
    void toolCall_stsMintFailure_failsClosed() {
        givenTool();
        when(hopTokenMinter.mintForHop(any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new StsMintException("no signing key for tenant"));

        McpSchema.CallToolResult result =
                orchestrator.orchestrate(exchange, TOOL_PUBLIC, Map.of("title", "x"));

        assertThat(result.isError()).isTrue();
        verify(mcpClientService, never()).callTool(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("prompt: STS mint failure → FAIL-CLOSED (throws, southbound call never made)")
    void promptGet_stsMintFailure_failsClosed() {
        givenPrompt();
        when(hopTokenMinter.mintForHop(any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new StsMintException("no signing key for tenant"));

        assertThatThrownBy(() -> orchestrator.orchestrateGetPrompt(exchange, PROMPT_PUBLIC, Map.of()))
                .isInstanceOf(RuntimeException.class);

        verify(mcpClientService, never()).getPrompt(any(), any(), any());
    }

    @Test
    @DisplayName("resource: STS mint failure → FAIL-CLOSED (throws, southbound call never made)")
    void resourceRead_stsMintFailure_failsClosed() {
        givenResource();
        when(hopTokenMinter.mintForHop(any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new StsMintException("no signing key for tenant"));

        assertThatThrownBy(() -> orchestrator.orchestrateReadResource(exchange, RESOURCE_PUBLIC, RESOURCE_URI))
                .isInstanceOf(RuntimeException.class);

        verify(mcpClientService, never()).readResource(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("tool: a governed hop mints an OBO token for the resolved session")
    void toolCall_mintsOboTokenForHop() {
        givenTool();
        when(mcpClientService.callTool(any(), eq(SERVER), eq(TOOL_ORIGINAL), any(), any(), any()))
                .thenReturn(List.of());

        orchestrator.orchestrate(exchange, TOOL_PUBLIC, Map.of("title", "x"));

        verify(hopTokenMinter).mintForHop(any(), eq("test-session"), any(), any(), any(), anyInt());
    }

    // ── Prompt / resource denial paths (only happy paths were covered before) ──────────────

    @Test
    @DisplayName("prompt: PDP denied → throws, southbound call never made")
    void promptGet_pdpDenied() {
        givenPrompt();
        when(cedarPolicyEngine.evaluate(any(), any()))
                .thenReturn(PolicyEvaluationResult.deny(Set.of("blocking-policy"), "not permitted", 1L));

        assertThatThrownBy(() -> orchestrator.orchestrateGetPrompt(exchange, PROMPT_PUBLIC, Map.of()))
                .isInstanceOf(RuntimeException.class);

        verify(mcpClientService, never()).getPrompt(any(), any(), any());
    }

    @Test
    @DisplayName("resource: PDP denied → throws, southbound call never made")
    void resourceRead_pdpDenied() {
        givenResource();
        when(cedarPolicyEngine.evaluate(any(), any()))
                .thenReturn(PolicyEvaluationResult.deny(Set.of("blocking-policy"), "not permitted", 1L));

        assertThatThrownBy(() -> orchestrator.orchestrateReadResource(exchange, RESOURCE_PUBLIC, RESOURCE_URI))
                .isInstanceOf(RuntimeException.class);

        verify(mcpClientService, never()).readResource(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("prompt: deprovisioned agent → denied at orchestrator, southbound call never made")
    void promptGet_governanceDeprovisioned() {
        givenPrompt();
        when(agentRegistryService.getAgentLifecycleStatusForSession(any())).thenReturn("DEPROVISIONED");

        assertThatThrownBy(() -> orchestrator.orchestrateGetPrompt(exchange, PROMPT_PUBLIC, Map.of()))
                .isInstanceOf(RuntimeException.class);

        verify(mcpClientService, never()).getPrompt(any(), any(), any());
    }

    @Test
    @DisplayName("resource: deprovisioned agent → denied at orchestrator, southbound call never made")
    void resourceRead_governanceDeprovisioned() {
        givenResource();
        when(agentRegistryService.getAgentLifecycleStatusForSession(any())).thenReturn("DEPROVISIONED");

        assertThatThrownBy(() -> orchestrator.orchestrateReadResource(exchange, RESOURCE_PUBLIC, RESOURCE_URI))
                .isInstanceOf(RuntimeException.class);

        verify(mcpClientService, never()).readResource(any(), any(), any(), any(), any());
    }
}
