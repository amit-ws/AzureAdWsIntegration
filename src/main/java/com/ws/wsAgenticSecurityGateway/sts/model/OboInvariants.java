package com.ws.wsAgenticSecurityGateway.sts.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codified OBO delegation-chain invariants (Hardening 10). Every minted token's {@link ActChain} must satisfy
 * these; they are auto-checked when the chain is grown (build time) and when it is sealed into a token (mint
 * time), and the {@link Result} is embedded in the token's {@code obo_invariants} claim so the audit receipt is
 * faithful to the token it represents. The structural invariants fail closed — a broken lineage is a
 * corrupted/forged delegation and must never mint.
 *
 * <ul>
 *   <li><b>prefix-preserved</b> — a hop may only APPEND; the prior chain is an exact prefix of the grown one.</li>
 *   <li><b>append-only</b> — the chain grows by at most one actor per hop (never rewrites or shrinks).</li>
 *   <li><b>sub-constant</b> — the root (index 0, the human/NHI on whose behalf all runs) is unchanged across the hop.</li>
 *   <li><b>root-present + monotonic-roles</b> — index 0 is a HUMAN/NHI root; every later principal is an AGENT actor.</li>
 *   <li><b>all-verified</b> — every principal is strongly authenticated (reported; the PDP lineage guardrails enforce the deny).</li>
 *   <li><b>scope-present</b> — the token carries a non-blank scope (the capability it is minted for).</li>
 * </ul>
 */
public final class OboInvariants {

    private OboInvariants() {
    }

    public record Result(boolean prefixPreserved, boolean appendOnly, boolean subConstant,
                         boolean rootPresent, boolean monotonicRoles, boolean allVerified,
                         boolean scopePresent, List<String> violations) {

        /** The hard, fail-closed invariants — a broken structure means a corrupted/forged lineage. */
        public boolean structurallyValid() {
            return prefixPreserved && appendOnly && subConstant && rootPresent && monotonicRoles;
        }

        /** Every invariant, including the reported (non-fail-closed) all-verified / scope-present. */
        public boolean allHold() {
            return structurallyValid() && allVerified && scopePresent;
        }

        /** Compact claim form embedded in the token + surfaced in the OBO receipt (keeps the receipt faithful). */
        public Map<String, Object> toClaim() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("structurallyValid", structurallyValid());
            m.put("prefixPreserved", prefixPreserved);
            m.put("appendOnly", appendOnly);
            m.put("subConstant", subConstant);
            m.put("rootPresent", rootPresent);
            m.put("monotonicRoles", monotonicRoles);
            m.put("allVerified", allVerified);
            m.put("scopePresent", scopePresent);
            if (!violations.isEmpty()) {
                m.put("violations", violations);
            }
            return m;
        }
    }

    /**
     * Check the invariants for a hop. {@code prior} is the inbound (pre-hop) chain and may be null/empty for a
     * root hop, in which case the growth invariants hold vacuously; {@code chain} is the chain about to be sealed;
     * {@code scope} is the capability the token is minted for.
     */
    public static Result check(ActChain prior, ActChain chain, String scope) {
        List<String> v = new ArrayList<>();
        List<Principal> ps = chain == null ? List.of() : chain.principals();
        List<Principal> pr = prior == null ? List.of() : prior.principals();

        // --- growth invariants (relative to the prior chain; vacuously true on a root hop) ---
        boolean prefixPreserved = true, appendOnly = true, subConstant = true;
        if (!pr.isEmpty()) {
            appendOnly = ps.size() >= pr.size() && ps.size() <= pr.size() + 1;
            if (!appendOnly) {
                v.add("chain grew by " + (ps.size() - pr.size()) + " (expected +0 or +1)");
            }
            prefixPreserved = ps.size() >= pr.size() && ps.subList(0, pr.size()).equals(pr);
            if (!prefixPreserved) {
                v.add("prior lineage prefix was not preserved");
            }
            subConstant = !ps.isEmpty() && ps.get(0).id().equals(pr.get(0).id());
            if (!subConstant) {
                v.add("root (sub) changed across the hop");
            }
        }

        // --- token well-formedness invariants ---
        boolean rootPresent = !ps.isEmpty();
        if (!rootPresent) {
            v.add("chain has no root principal");
        }
        boolean monotonicRoles = rootPresent
                && (ps.get(0).type() == Principal.PrincipalType.HUMAN
                    || ps.get(0).type() == Principal.PrincipalType.NHI);
        for (int i = 1; i < ps.size(); i++) {
            if (ps.get(i).type() != Principal.PrincipalType.AGENT) {
                monotonicRoles = false;
                break;
            }
        }
        if (rootPresent && !monotonicRoles) {
            v.add("roles not monotonic (root must be human/nhi, every actor must be an agent)");
        }

        boolean allVerified = !ps.isEmpty() && ps.stream().allMatch(Principal::verified);
        if (rootPresent && !allVerified) {
            v.add("chain contains an unverified principal");
        }

        boolean scopePresent = scope != null && !scope.isBlank();
        if (!scopePresent) {
            v.add("minted token carries no scope");
        }

        return new Result(prefixPreserved, appendOnly, subConstant, rootPresent, monotonicRoles,
                allVerified, scopePresent, List.copyOf(v));
    }
}
