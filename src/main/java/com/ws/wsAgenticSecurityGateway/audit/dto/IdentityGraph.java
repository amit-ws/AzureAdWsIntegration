package com.ws.wsAgenticSecurityGateway.audit.dto;

import java.util.List;

/**
 * A relationship graph over the audit trail: <b>who acts through whom, and what they invoke</b>.
 *
 * <p>Nodes are humans, agents and tools; links are {@code acts-through} (human → agent) and
 * {@code invoked} (agent → tool, carrying allow/deny counts). It is a pure read-model — every node and
 * edge is derived from existing audit rows, so it unifies session and stateless activity into one picture
 * and needs no new data capture. The agent's {@code act_chain} is literally a path through this graph.
 */
public record IdentityGraph(List<Node> nodes, List<Link> links) {

    /**
     * @param id        stable node id ({@code human:<identity>}, {@code agent:<clientId>}, {@code tool:<name>})
     * @param type      {@code HUMAN} / {@code AGENT} / {@code TOOL}
     * @param label     display name
     * @param sublabel  secondary text (client id for agents, server for tools)
     * @param requests  total request-legs incident to this node (used for sizing)
     */
    public record Node(String id, String type, String label, String sublabel, long requests) {
    }

    /**
     * @param source  source node id
     * @param target  target node id
     * @param type    {@code acts-through} or {@code invoked}
     * @param count   total requests along this edge
     * @param allowed allowed count (invoked edges)
     * @param denied  denied count (invoked edges)
     */
    public record Link(String source, String target, String type, long count, long allowed, long denied) {
    }
}
