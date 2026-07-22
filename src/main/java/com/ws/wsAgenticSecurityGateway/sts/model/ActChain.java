package com.ws.wsAgenticSecurityGateway.sts.model;

import java.util.List;
import java.util.Map;

/**
 * The delegation lineage carried in a minted token's {@code act_chain} claim: an ordered list of
 * {@link Principal}s, root first (index 0 = the human/NHI on whose behalf everything runs), actor last
 * (the agent currently acting).
 */
public final class ActChain {

    private final List<Principal> principals;

    public ActChain(List<Principal> principals) {
        this.principals = List.copyOf(principals);
    }

    public List<Principal> principals() {
        return principals;
    }

    public boolean isEmpty() {
        return principals.isEmpty();
    }

    /** The root principal (index 0), or null if empty. */
    public Principal root() {
        return principals.isEmpty() ? null : principals.get(0);
    }

    /** The current actor (last element), or null if empty. */
    public Principal actor() {
        return principals.isEmpty() ? null : principals.get(principals.size() - 1);
    }

    /** The {@code act_chain} claim value: ordered principal claim maps (root first, actor last). */
    public List<Map<String, Object>> toClaim() {
        return principals.stream().map(Principal::toClaim).toList();
    }
}
