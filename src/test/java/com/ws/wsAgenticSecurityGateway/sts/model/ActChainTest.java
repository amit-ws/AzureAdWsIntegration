package com.ws.wsAgenticSecurityGateway.sts.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the delegation-ORDER invariants the audit/policy layers rely on:
 * the flat {@code toClaim()} is always root-first / current-actor-last, and the nested RFC 8693
 * {@code toActClaim()} is current-actor-outermost. A refactor that reorders either would fail here.
 */
class ActChainTest {

    private final Principal root = Principal.human("amit", "amit-prakash", "kc", true);
    private final Principal agent1 = Principal.agent("agent1", "claude-desktop", "KEYCLOAK", true);
    private final Principal agent2 = Principal.agent("agent2", "sub-agent", "KEYCLOAK", true);

    @Test
    void flatClaim_keepsRootFirstAndCurrentActorLast() {
        List<Map<String, Object>> flat = new ActChain(List.of(root, agent1, agent2)).toClaim();

        assertThat(flat).hasSize(3);
        assertThat(flat.get(0)).containsEntry("id", "amit").containsEntry("type", "human");  // root pinned first
        assertThat(flat.get(1)).containsEntry("id", "agent1");
        assertThat(flat.get(2)).containsEntry("id", "agent2");                                // current actor last
    }

    @Test
    void nestedActClaim_isCurrentActorOutermost() {
        Map<String, Object> act = new ActChain(List.of(root, agent1, agent2)).toActClaim();

        // Current actor (agent2) outermost; prior actor (agent1) nested; root is the token sub, not in here.
        assertThat(act).containsEntry("sub", "agent2");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) act.get("act");
        assertThat(nested).containsEntry("sub", "agent1");
        assertThat(nested).doesNotContainKey("act");
    }

    @Test
    void nestedActClaim_isNullWhenRootOnly() {
        assertThat(new ActChain(List.of(root)).toActClaim()).isNull();
    }
}
