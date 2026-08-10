package com.ws.wsAgenticSecurityGateway.sts.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codified OBO delegation-chain invariants (Hardening 10). Pins each invariant — prefix-preserved, append-only,
 * sub-constant, monotonic roles, all-verified, scope-present — so a regression in the chain-building spine is
 * caught here rather than silently minting a corrupted delegation token.
 */
class OboInvariantsTest {

    private static Principal human(String id, boolean verified) {
        return Principal.human(id, id, "https://idp", verified);
    }

    private static Principal nhi(String id) {
        return Principal.nhi(id, true);
    }

    private static Principal agent(String id, boolean verified) {
        return Principal.agent(id, id, verified);
    }

    private static ActChain chain(Principal... ps) {
        return new ActChain(List.of(ps));
    }

    @Test
    void rootOnly_humanVerified_allHold() {
        OboInvariants.Result r = OboInvariants.check(null, chain(human("alice", true)), "mcp:tool:github_get_me");
        assertThat(r.structurallyValid()).isTrue();
        assertThat(r.allHold()).isTrue();
        assertThat(r.violations()).isEmpty();
    }

    @Test
    void growth_appendOnly_preservesPrefix_isValid() {
        ActChain prior = chain(human("alice", true), agent("advisor", true));
        ActChain grown = chain(human("alice", true), agent("advisor", true), agent("market-data", true));
        OboInvariants.Result r = OboInvariants.check(prior, grown, "a2a:skill:market-data.quote");
        assertThat(r.prefixPreserved()).isTrue();
        assertThat(r.appendOnly()).isTrue();
        assertThat(r.subConstant()).isTrue();
        assertThat(r.structurallyValid()).isTrue();
    }

    @Test
    void growth_noAppend_selfCall_isValid() {
        ActChain prior = chain(human("alice", true), agent("advisor", true));
        OboInvariants.Result r = OboInvariants.check(prior, prior, "a2a:skill:x");
        assertThat(r.appendOnly()).isTrue();
        assertThat(r.prefixPreserved()).isTrue();
        assertThat(r.structurallyValid()).isTrue();
    }

    @Test
    void prefixRewritten_failsStructural() {
        ActChain prior = chain(human("alice", true), agent("advisor", true));
        ActChain tampered = chain(human("alice", true), agent("attacker", true), agent("market-data", true));
        OboInvariants.Result r = OboInvariants.check(prior, tampered, "a2a:skill:x");
        assertThat(r.prefixPreserved()).isFalse();
        assertThat(r.structurallyValid()).isFalse();
        assertThat(r.violations()).anyMatch(s -> s.contains("prefix"));
    }

    @Test
    void grewByTwo_failsAppendOnly() {
        ActChain prior = chain(human("alice", true), agent("advisor", true));
        ActChain grown = chain(human("alice", true), agent("advisor", true), agent("m", true), agent("n", true));
        OboInvariants.Result r = OboInvariants.check(prior, grown, "s");
        assertThat(r.appendOnly()).isFalse();
        assertThat(r.structurallyValid()).isFalse();
    }

    @Test
    void rootChanged_failsSubConstant() {
        ActChain prior = chain(human("alice", true), agent("advisor", true));
        ActChain grown = chain(human("mallory", true), agent("advisor", true), agent("m", true));
        OboInvariants.Result r = OboInvariants.check(prior, grown, "s");
        assertThat(r.subConstant()).isFalse();
        assertThat(r.structurallyValid()).isFalse();
    }

    @Test
    void nonMonotonicRoles_fails() {
        OboInvariants.Result r = OboInvariants.check(null, chain(human("alice", true), human("bob", true)), "s");
        assertThat(r.monotonicRoles()).isFalse();
        assertThat(r.structurallyValid()).isFalse();
    }

    @Test
    void nhiRoot_isMonotonic() {
        OboInvariants.Result r = OboInvariants.check(null, chain(nhi("svc-1"), agent("advisor", true)), "s");
        assertThat(r.monotonicRoles()).isTrue();
        assertThat(r.structurallyValid()).isTrue();
    }

    @Test
    void unverifiedPrincipal_reportedButStructurallyValid() {
        OboInvariants.Result r = OboInvariants.check(null, chain(human("alice", false), agent("advisor", true)), "s");
        assertThat(r.structurallyValid()).isTrue();   // structure is fine
        assertThat(r.allVerified()).isFalse();          // but reported unverified — the PDP guardrails own the deny
        assertThat(r.allHold()).isFalse();
    }

    @Test
    void noScope_reported() {
        OboInvariants.Result r = OboInvariants.check(null, chain(human("alice", true)), "  ");
        assertThat(r.scopePresent()).isFalse();
        assertThat(r.structurallyValid()).isTrue();
    }

    @Test
    void emptyChain_hasNoRoot() {
        OboInvariants.Result r = OboInvariants.check(null, new ActChain(List.of()), "s");
        assertThat(r.rootPresent()).isFalse();
        assertThat(r.structurallyValid()).isFalse();
    }

    @Test
    void toClaim_isFaithful() {
        OboInvariants.Result r = OboInvariants.check(null, chain(human("alice", true), agent("advisor", true)), "s");
        var claim = r.toClaim();
        assertThat(claim).containsEntry("structurallyValid", true).containsEntry("allVerified", true);
        assertThat(claim).doesNotContainKey("violations"); // no violations → key omitted
    }
}
