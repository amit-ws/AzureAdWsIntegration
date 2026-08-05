package com.ws.wsAgenticSecurityGateway.sts.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.sts.model.ActChain;
import com.ws.wsAgenticSecurityGateway.sts.model.Principal;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Multi-hop (Band 6) behavior of {@link ActChainBuilder}: a downstream A2A leg arrives bearing a
 * gateway-minted OBO whose {@code act_chain} already holds the verified root + prior actors. The builder
 * must <em>extend</em> that lineage (append this hop's agent), not rebuild a fresh root — while a first hop
 * (no inbound {@code act_chain}) keeps the original root-building behavior.
 */
class ActChainBuilderMultiHopTest {

    private final AgentRegistryService registry = mock(AgentRegistryService.class);
    private final ActChainBuilder builder = new ActChainBuilder(registry);

    @Test
    void extendsInboundActChain_appendingThisHopsAgent() {
        // inbound OBO carries a full act_chain: human H -> agent A
        List<Map<String, Object>> inboundChain = List.of(
                Principal.human("user-h", "alice", "keycloak", true).toClaim(),
                Principal.agent("agent-a", "client-a", true).toClaim());
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("rawJwtClaims", Map.of("act_chain", inboundChain));
        ctx.put("agentClientId", "client-b"); // this hop's caller = agent B

        ActChain chain = builder.fromTransportContext(ctx, null);

        // prior lineage [H, A] preserved, B appended, verified root carried forward
        assertThat(chain.principals()).extracting(Principal::id)
                .containsExactly("user-h", "agent-a", "client-b");
        assertThat(chain.root().id()).isEqualTo("user-h");
        assertThat(chain.root().verified()).isTrue();
        assertThat(chain.actor().id()).isEqualTo("client-b");
    }

    @Test
    void firstHop_noInboundChain_buildsRootAndActor() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("jwtSubject", "user-h");
        ctx.put("userIdentity", "alice");
        ctx.put("agentClientId", "client-a");

        ActChain chain = builder.fromTransportContext(ctx, null);

        // original behavior: inferred human root (no session) -> calling agent
        assertThat(chain.principals()).extracting(Principal::id)
                .containsExactly("user-h", "client-a");
        assertThat(chain.actor().id()).isEqualTo("client-a");
    }
}
