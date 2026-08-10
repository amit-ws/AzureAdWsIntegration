package com.ws.wsAgenticSecurityGateway.audit.dto;

import java.util.List;

/**
 * A trace-scoped delegation DAG — human → agent(s) → tool(s), INCLUDING agent→agent delegation, which the
 * global {@link IdentityGraph} (human→agent→tool only) cannot express. Built read-only from a single trace's
 * audit events for the dashboard's "View DAG" governance-trail view. Purely additive: the flat
 * {@code /logs/trace/{traceId}} endpoint is untouched, so existing consumers (the console) are unaffected.
 */
public record TraceGraph(String traceId, List<Node> nodes, List<Edge> edges, List<Blocked> blocked) {

    /** {@code level} = longest delegation depth from the human root (0), for a left→right layered layout. */
    public record Node(String id, String type, String label, String sublabel, int level) {}

    /** One directed call. {@code decision}: {@code "ALLOW"} / {@code "DENY"} / {@code null}. */
    public record Edge(String source, String target, String capability, String decision) {}

    /** A denied leg — surfaced as the "blocked" banner. */
    public record Blocked(String from, String capability, String policyId, String reason) {}
}
