package com.ws.wsAgenticSecurityGateway.sts.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * Rebuild a chain from an inbound token's {@code act_chain} claim (the inverse of {@link #toClaim()}) — used
     * to extend the lineage across a multi-hop A2A leg. A null / non-list / empty claim yields an empty chain.
     */
    @SuppressWarnings("unchecked")
    public static ActChain fromClaim(Object claim) {
        List<Principal> principals = new ArrayList<>();
        if (claim instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Principal p = Principal.fromClaim((Map<String, Object>) map);
                    if (p != null) {
                        principals.add(p);
                    }
                }
            }
        }
        return new ActChain(principals);
    }

    /**
     * The token's RFC 8693 {@code act} (actor) claim: a nested actor chain in which the current actor is
     * outermost and each prior actor is nested inside via its own {@code act}, keyed by {@code sub}. The
     * root (index 0) is carried as the token {@code sub}, not inside this claim. Returns {@code null} when
     * there is no delegated actor (root only). This is the standards-interop form; {@link #toClaim()} stays
     * the gateway's richer flat form used for audit and policy.
     */
    public Map<String, Object> toActClaim() {
        if (principals.size() < 2) {
            return null; // root only — no actor to express
        }
        Map<String, Object> act = null;
        for (int i = 1; i < principals.size(); i++) { // actors in delegation order; each wraps the previous
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("sub", principals.get(i).id());
            if (act != null) {
                node.put("act", act);
            }
            act = node;
        }
        return act;
    }
}
