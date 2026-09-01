package com.ws.wsAgenticSecurityGateway.accessgraph.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The Identity Access Graph — one read-model that answers, on a single page: <b>who can reach what, who actually
 * did, where the risk is, and what to fix</b>.
 *
 * <p>A superset of the legacy {@link com.ws.wsAgenticSecurityGateway.audit.dto.IdentityGraph}: every edge carries
 * BOTH the <b>Observed</b> reach (audit ledger — what actually happened) and the <b>Entitled</b> reach (Cedar
 * policy — what is permitted), so the client toggles Observed / Entitled / Gap overlays from a single fetch. Pure
 * read-model — assembled from data already captured (audit, PDP decisions, policy, egress classification); it
 * never participates in a decision.
 *
 * <p>Principals ("who"): {@code HUMAN}, {@code NHI}, {@code AGENT}. Targets ("what"): {@code AGENT} (as an A2A
 * callee), {@code TOOL}, {@code SKILL}, {@code SERVER}.
 */
public record AccessGraph(List<Node> nodes, List<Edge> edges, Summary summary) {

    /**
     * A node in the graph.
     *
     * @param id       stable id ({@code human:<identity>}, {@code nhi:<identity>}, {@code agent:<name>},
     *                 {@code tool:<name>}, {@code skill:<name>}, {@code server:<name>})
     * @param type     {@code HUMAN} / {@code NHI} / {@code AGENT} / {@code TOOL} / {@code SKILL} / {@code SERVER}
     * @param label    display name
     * @param sublabel secondary text (client id for agents, server for tools)
     * @param requests  observed request volume incident to this node (used for sizing)
     * @param dataClass peak egress sensitivity for a TOOL/SERVER node ({@code RESTRICTED} / {@code CONFIDENTIAL}
     *                  / {@code INTERNAL} / {@code PUBLIC}), {@code null} when unclassified or not applicable
     * @param riskBand  {@code CRITICAL} / {@code HIGH} / {@code MEDIUM} / {@code LOW} / {@code NONE}
     * @param flags     risk markers, e.g. {@code OVER_PRIVILEGED}, {@code SENSITIVE_REACH}, {@code RESTRICTED_DATA},
     *                  {@code CONFIDENTIAL_DATA}, {@code PROBING}, {@code BROAD_GRANT}
     */
    public record Node(String id, String type, String label, String sublabel,
                       long requests, String dataClass, String riskBand, List<String> flags) {}

    /**
     * A directed relationship, carrying both overlays so the client can render Observed / Entitled / Gap without
     * re-fetching.
     *
     * @param source      source node id
     * @param target      target node id
     * @param relation    {@code acts-through} (identity → agent) / {@code invoked} (agent → tool) /
     *                    {@code a2a} (agent → agent skill)
     * @param observed    true if the edge was actually exercised (audit)
     * @param count       total observed requests along the edge
     * @param allowed     observed allowed count
     * @param denied      observed denied count
     * @param lastUsed    last time the edge was exercised ({@code null} if never)
     * @param entitled    true if policy grants this reach
     * @param grantStatus {@code ACTIVE} / {@code CONDITIONAL} / {@code LATENT} / {@code BLOCKED} / {@code WILDCARD}
     *                    (covered by the agent's broad grant) — {@code null} if not entitled
     * @param viaPolicies policy names that grant/deny the edge
     * @param gap         {@code OVER_PRIVILEGE} (entitled but never used) / {@code UNGOVERNED_USE} (used but not
     *                    specifically granted) / {@code null} (observed and entitled agree)
     * @param riskBand    {@code CRITICAL} / {@code HIGH} / {@code MEDIUM} / {@code LOW} / {@code NONE}
     */
    public record Edge(String source, String target, String relation,
                       boolean observed, long count, long allowed, long denied, LocalDateTime lastUsed,
                       boolean entitled, String grantStatus, List<String> viaPolicies,
                       String gap, String riskBand) {}

    /** Headline counts for the overlay chips / KPIs. */
    public record Summary(int humans, int nhis, int agents, int tools, int skills, int servers,
                          int overPrivilegeEdges, int ungovernedUseEdges, int blockedEdges,
                          long allowed, long denied) {}
}
