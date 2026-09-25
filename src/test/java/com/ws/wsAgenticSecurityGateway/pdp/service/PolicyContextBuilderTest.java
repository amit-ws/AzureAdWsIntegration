package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.orchestration.model.ClientInfo;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestAttributeKeys;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestAttributes;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestContext;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the attributes-to-PDP mapping that B3 rewired: {@link PolicyContextBuilder} now reads a neutral
 * {@link RequestContext} instead of the MCP exchange. The governed-flow characterization tests mock this
 * builder entirely, so this is the only place the real key-by-key extraction (principal, JWT claims, roles,
 * source ip, and the stateless client-id fallback) is asserted.
 */
class PolicyContextBuilderTest {

    private CustomAttributeService customAttributeService;
    private PolicyContextBuilder builder;

    @BeforeEach
    void setUp() {
        AgentRegistryService agentRegistryService = mock(AgentRegistryService.class);
        customAttributeService = mock(CustomAttributeService.class);
        when(agentRegistryService.findAgentsByName(anyString())).thenReturn(List.of());
        when(customAttributeService.resolveAttributes(any(), any(), any())).thenReturn(Map.of());
        builder = new PolicyContextBuilder(agentRegistryService, customAttributeService, List.of());
    }

    private RequestContext requestContextWith(ClientInfo clientInfo, Map<String, Object> attrs) {
        RequestAttributes accessor = attrs::get;
        return new RequestContext(clientInfo, "sess-1", accessor);
    }

    @Test
    void mapsClientInfoAndAttributesIntoPolicyRequest() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RequestAttributeKeys.AGENT_CLIENT_ID, "client-9");
        attrs.put(RequestAttributeKeys.JWT_SUBJECT, "sub-1");
        attrs.put(RequestAttributeKeys.USER_IDENTITY, "alice");
        attrs.put(RequestAttributeKeys.TOKEN_TYPE, "Bearer");
        attrs.put(RequestAttributeKeys.CLIENT_IP, "10.0.0.5");
        attrs.put(RequestAttributeKeys.AGENT_ROLES, List.of("role-a", "role-b"));
        attrs.put(RequestAttributeKeys.REALM_ROLES, List.of("realm-x"));
        attrs.put(RequestAttributeKeys.CLIENT_ROLES, List.of("client-y"));
        attrs.put(RequestAttributeKeys.CUSTOM_CLAIMS, Map.of("dept", "eng"));
        RequestContext rc = requestContextWith(new ClientInfo("agent-x", "2.1"), attrs);

        PolicyEvaluationRequest req = builder.buildForToolCall(
                rc, "github_get_me", "github", "get_me", Map.of("a", 1), "corr-1", "sess-1");

        // Hardening 1 (proven identity): the VERIFIED credential (client_id "client-9") is the principal,
        // NOT the self-asserted clientInfo.name "agent-x" — a client cannot spoof its identity via clientInfo.
        assertThat(req.getAgentName()).isEqualTo("client-9");
        assertThat(req.getAgentVersion()).isEqualTo("2.1");
        assertThat(req.getAgentClientId()).isEqualTo("client-9");
        assertThat(req.getJwtSubject()).isEqualTo("sub-1");
        assertThat(req.getUserIdentity()).isEqualTo("alice");
        assertThat(req.getTokenType()).isEqualTo("Bearer");
        assertThat(req.getSourceIp()).isEqualTo("10.0.0.5");
        assertThat(req.getAgentRoles()).containsExactly("role-a", "role-b");
        assertThat(req.getRealmRoles()).containsExactly("realm-x");
        assertThat(req.getClientRoles()).containsExactly("client-y");
        assertThat(req.getJwtCustomClaims()).containsEntry("dept", "eng");
        assertThat(req.getResourceName()).isEqualTo("github_get_me");
        assertThat(req.getServerName()).isEqualTo("github");
    }

    @Test
    void fallsBackToAgentClientIdWhenClientInfoAbsent() {
        // Stateless bridge: no clientInfo -> the JWT client_id becomes the policy principal.
        Map<String, Object> attrs = Map.of(RequestAttributeKeys.AGENT_CLIENT_ID, "client-42");
        RequestContext rc = requestContextWith(null, attrs);

        PolicyEvaluationRequest req = builder.buildForToolCall(
                rc, "tool", "srv", "orig", Map.of(), "corr-2", "sess-2");

        assertThat(req.getAgentName()).isEqualTo("client-42");
    }

    @Test
    void unknownAgentWhenNoClientInfoAndNoClientId() {
        RequestContext rc = requestContextWith(null, Map.of());

        PolicyEvaluationRequest req = builder.buildForToolCall(
                rc, "tool", "srv", "orig", Map.of(), "corr-3", "sess-3");

        assertThat(req.getAgentName()).isEqualTo("unknown");
    }
}
