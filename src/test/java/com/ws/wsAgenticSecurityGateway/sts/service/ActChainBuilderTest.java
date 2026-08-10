package com.ws.wsAgenticSecurityGateway.sts.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.sts.model.ActChain;
import com.ws.wsAgenticSecurityGateway.sts.model.Principal;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ActChainBuilder} — mocked registry, verifying the delegation lineage for
 * human-delegated, machine-rooted, weak-human, and inbound-`act`-seeded cases, plus the claim shape.
 */
class ActChainBuilderTest {

    private final AgentRegistryService registry = mock(AgentRegistryService.class);
    private final ActChainBuilder builder = new ActChainBuilder(registry);

    private static final String SID = "sess-1";
    private final UUID agentId = UUID.randomUUID();
    private final UUID humanId = UUID.randomUUID();
    private final UUID nhiId = UUID.randomUUID();

    private Map<String, Object> ctx(String tokenType, String sub, String username, String clientId) {
        Map<String, Object> m = new HashMap<>();
        if (tokenType != null) m.put("tokenType", tokenType);
        if (sub != null) m.put("jwtSubject", sub);
        if (username != null) m.put("userIdentity", username);
        if (clientId != null) m.put("agentClientId", clientId);
        m.put("idpIssuer", "https://kc/realms/ws");
        return m;
    }

    @Test
    void humanDelegated_producesVerifiedHumanRoot_thenVerifiedAgentActor() {
        when(registry.getHumanUserIdForSession(SID)).thenReturn(humanId);
        when(registry.getAgentIdForSession(SID)).thenReturn(agentId);

        ActChain chain = builder.fromTransportContext(
                ctx("HUMAN_DELEGATED", "sarah@acme.com", "sarah", "agent-client"), SID);

        assertThat(chain.principals()).hasSize(2);
        Principal root = chain.root();
        assertThat(root.type()).isEqualTo(Principal.PrincipalType.HUMAN);
        assertThat(root.id()).isEqualTo("sarah@acme.com");
        assertThat(root.verified()).isTrue();
        Principal actor = chain.actor();
        assertThat(actor.type()).isEqualTo(Principal.PrincipalType.AGENT);
        assertThat(actor.id()).isEqualTo(agentId.toString());
        assertThat(actor.workloadId()).isEqualTo("agent-client");
        assertThat(actor.verified()).isTrue();
    }

    @Test
    void sessionlessValidatedHumanToken_verifiesRootAndActor_forA2a() {
        // A2A / stateless: no server-side session, so no registry agentId/humanId — identity rides on the
        // validated token (HUMAN_DELEGATED is only assigned after the resource server validated it, and the
        // IdP-asserted username identifies a known human).
        when(registry.getHumanUserIdForSession(SID)).thenReturn(null);
        when(registry.getAgentIdForSession(SID)).thenReturn(null);

        ActChain chain = builder.fromTransportContext(
                ctx("HUMAN_DELEGATED", "689d-sub", "amit-prakash", "claude-desktop"), SID);

        assertThat(chain.principals()).hasSize(2);
        Principal root = chain.root();
        assertThat(root.type()).isEqualTo(Principal.PrincipalType.HUMAN);
        assertThat(root.id()).isEqualTo("689d-sub");
        assertThat(root.verified()).isTrue();
        Principal actor = chain.actor();
        assertThat(actor.type()).isEqualTo(Principal.PrincipalType.AGENT);
        assertThat(actor.id()).isEqualTo("claude-desktop");
        assertThat(actor.verified()).isTrue();
    }

    @Test
    void sessionlessWithoutValidatedToken_leavesRootAndActorUnverified() {
        // auth-mode=none (no tokenType) → not a validated token: never fabricate verification.
        when(registry.getHumanUserIdForSession(SID)).thenReturn(null);
        when(registry.getAgentIdForSession(SID)).thenReturn(null);

        ActChain chain = builder.fromTransportContext(
                ctx(null, "sub-x", "someone", "some-client"), SID);

        assertThat(chain.root().verified()).isFalse();
        assertThat(chain.actor().verified()).isFalse();
    }

    @Test
    void automated_producesNhiRoot() {
        when(registry.getNhiIdForSession(SID)).thenReturn(nhiId);
        when(registry.getAgentIdForSession(SID)).thenReturn(agentId);

        ActChain chain = builder.fromTransportContext(
                ctx("AUTOMATED_AGENT", "service-account-x", null, "agent-client"), SID);

        assertThat(chain.root().type()).isEqualTo(Principal.PrincipalType.NHI);
        assertThat(chain.root().id()).isEqualTo(nhiId.toString());
        assertThat(chain.actor().type()).isEqualTo(Principal.PrincipalType.AGENT);
    }

    @Test
    void weakHuman_withNoRecord_marksRootUnverified_neverFabricates() {
        when(registry.getHumanUserIdForSession(SID)).thenReturn(null);
        when(registry.getNhiIdForSession(SID)).thenReturn(null);
        when(registry.getAgentIdForSession(SID)).thenReturn(agentId);

        ActChain chain = builder.fromTransportContext(
                ctx("HUMAN_DELEGATED", "maybe@x", null, "agent-client"), SID);

        assertThat(chain.root().type()).isEqualTo(Principal.PrincipalType.HUMAN);
        assertThat(chain.root().id()).isEqualTo("maybe@x");
        assertThat(chain.root().verified()).isFalse();
    }

    @Test
    void seedsPriorActorFromInboundActClaim() {
        when(registry.getHumanUserIdForSession(SID)).thenReturn(humanId);
        when(registry.getAgentIdForSession(SID)).thenReturn(agentId);

        Map<String, Object> ctx = ctx("HUMAN_DELEGATED", "sarah@acme.com", "sarah", "agent-client");
        ctx.put("rawJwtClaims", Map.of("act", Map.of("sub", "upstream-agent")));

        ActChain chain = builder.fromTransportContext(ctx, SID);

        // root(human) -> prior actor(from inbound act) -> our agent(actor)
        assertThat(chain.principals()).hasSize(3);
        assertThat(chain.principals().get(1).id()).isEqualTo("upstream-agent");
        assertThat(chain.principals().get(1).type()).isEqualTo(Principal.PrincipalType.AGENT);
        assertThat(chain.principals().get(1).verified()).isFalse();
        assertThat(chain.actor().id()).isEqualTo(agentId.toString());
    }

    @Test
    void toClaim_matchesLockedShape() {
        when(registry.getHumanUserIdForSession(SID)).thenReturn(humanId);
        when(registry.getAgentIdForSession(SID)).thenReturn(agentId);

        List<Map<String, Object>> claim = builder.fromTransportContext(
                ctx("HUMAN_DELEGATED", "sarah@acme.com", "sarah", "agent-client"), SID).toClaim();

        assertThat(claim).hasSize(2);
        assertThat(claim.get(0)).containsEntry("type", "human").containsEntry("verified", true)
                .containsEntry("id", "sarah@acme.com").containsEntry("username", "sarah");
        assertThat(claim.get(1)).containsEntry("type", "agent").containsEntry("verified", true)
                .containsEntry("workload_id", "agent-client").containsEntry("identity_source", "KEYCLOAK")
                .containsEntry("clientId", "agent-client");   // back-compat key preserved for external consumers
    }
}
