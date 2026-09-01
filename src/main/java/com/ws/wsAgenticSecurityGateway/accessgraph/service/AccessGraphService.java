package com.ws.wsAgenticSecurityGateway.accessgraph.service;

import com.ws.wsAgenticSecurityGateway.accessgraph.dto.AccessGraph;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius.ResourceReach;
import com.ws.wsAgenticSecurityGateway.ciso.service.BlastRadiusService;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles the {@link AccessGraph} — the Identity Access Graph read-model.
 *
 * <p>Two overlays, merged per edge:
 * <ul>
 *   <li><b>Observed</b> — the audit read-model (actor→agent {@code acts-through} + agent→tool {@code invoked}
 *       with allow/deny), typing the actor HUMAN vs NHI from whether the root is machine-rooted.</li>
 *   <li><b>Entitled</b> — per agent, the policy-derived reach from {@link BlastRadiusService} (each
 *       {@code ResourceReach} carries a grant {@code status} AND whether it was actually {@code used}), so the
 *       Gap — granted-but-never-used (over-privilege) and used-but-ungranted (drift) — falls straight out.</li>
 * </ul>
 * Egress sensitivity (from the DLP classification ledger) is layered onto tool nodes and the agents that reach
 * them. Pure read-model; never participates in a decision. One agent's failure never breaks the whole graph.
 */
@Service
@Slf4j
public class AccessGraphService {

    /** Grant statuses that represent a real (or dormant-but-real) entitlement, vs {@code USED_ONLY}. */
    private static final Set<String> ENTITLED_STATUSES = Set.of("ACTIVE", "CONDITIONAL", "LATENT", "BLOCKED");

    private final GatewayAuditLogRepository auditRepo;
    private final BlastRadiusService blastRadiusService;
    private final GatewayResponseClassificationRepository classificationRepo;

    public AccessGraphService(GatewayAuditLogRepository auditRepo, BlastRadiusService blastRadiusService,
                              GatewayResponseClassificationRepository classificationRepo) {
        this.auditRepo = auditRepo;
        this.blastRadiusService = blastRadiusService;
        this.classificationRepo = classificationRepo;
    }

    public AccessGraph build(Integer hours) {
        String tenant = TenantContext.get();
        int windowHours = (hours != null && hours > 0) ? hours : 24 * 365; // ~1y default ≈ all-time
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);

        Map<String, NodeAcc> nodes = new LinkedHashMap<>();
        Map<String, EdgeAcc> edges = new LinkedHashMap<>();
        Map<String, String> agentSubject = new LinkedHashMap<>(); // agentNodeId -> agent name (the PDP subject / policy principal)

        // ── OBSERVED: actor → agent (acts-through), typed HUMAN vs NHI by whether the root is machine-rooted ──
        for (Object[] r : auditRepo.aggregateActorAgentEdges(tenant, since)) {
            String userIdentity = (String) r[0];
            String humanUserId = (String) r[1];
            String nhiId = (String) r[2];
            String agentName = (String) r[3];
            String agentClientId = (String) r[4];
            long count = ((Number) r[5]).longValue();
            if (agentName == null) continue;

            boolean nhi = nhiId != null;                                   // machine-rooted actor (no human root)
            String actorLabel = userIdentity != null ? userIdentity : (nhi ? nhiId : humanUserId);
            if (actorLabel == null) continue;
            String actorId = (nhi ? "nhi:" : "human:") + actorLabel;
            String agentId = "agent:" + (agentClientId != null ? agentClientId : agentName);

            node(nodes, actorId, nhi ? "NHI" : "HUMAN", actorLabel, nhi ? "workload" : "human");
            node(nodes, agentId, "AGENT", agentName, agentClientId);
            agentSubject.put(agentId, agentName);
            observedEdge(edges, actorId, "acts-through", agentId, count, 0, 0, null);
        }

        // ── OBSERVED: agent → tool (invoked, with allow/deny) ──
        for (Object[] r : auditRepo.aggregateAgentToolEdges(AuditEventType.PDP_DECISION_RENDERED, tenant, since)) {
            String agentName = (String) r[0];
            String agentClientId = (String) r[1];
            String capability = (String) r[2];
            String serverName = (String) r[3];
            AuditStatus status = (AuditStatus) r[4];
            long count = ((Number) r[5]).longValue();
            if (agentName == null || capability == null) continue;

            String agentId = "agent:" + (agentClientId != null ? agentClientId : agentName);
            String toolId = "tool:" + capability;

            node(nodes, agentId, "AGENT", agentName, agentClientId);
            node(nodes, toolId, "TOOL", capability, serverName);
            agentSubject.put(agentId, agentName);
            long allowed = status == AuditStatus.SUCCESS ? count : 0;
            long denied = status == AuditStatus.DENIED ? count : 0;
            observedEdge(edges, agentId, "invoked", toolId, count, allowed, denied, null);
        }

        // ── ENTITLED overlay: per agent, the policy-derived reach (+ used) from the blast-radius engine ──
        for (Map.Entry<String, String> ag : agentSubject.entrySet()) {
            String agentId = ag.getKey();
            AgentBlastRadius br;
            try {
                br = blastRadiusService.getAgentBlastRadius(ag.getValue());
            } catch (Exception e) {
                log.warn("access-graph: blast radius failed for agent {} — skipping its entitled overlay", ag.getValue(), e);
                continue;
            }
            NodeAcc agentNode = nodes.get(agentId);
            if (agentNode != null && br.reachesAnyResource()) agentNode.flags.add("BROAD_GRANT");

            for (ResourceReach rr : br.reach()) {
                boolean isSkill = "SKILL".equals(rr.resourceKind());
                String relation = isSkill ? "a2a" : "invoked";
                String targetId;
                if (isSkill) {
                    String targetAgent = rr.resourceId().contains(".")
                            ? rr.resourceId().substring(0, rr.resourceId().indexOf('.'))
                            : rr.resourceId();
                    targetId = "agent:" + targetAgent;
                    node(nodes, targetId, "AGENT", targetAgent, null);
                } else {
                    targetId = "tool:" + rr.resourceId();
                    node(nodes, targetId, "TOOL", rr.resourceId(), null);
                }

                EdgeAcc edge = edges.computeIfAbsent(edgeKey(agentId, relation, targetId),
                        k -> new EdgeAcc(agentId, relation, targetId));

                if (ENTITLED_STATUSES.contains(rr.status())) {
                    edge.entitled = true;
                    edge.grantStatus = rr.status();
                    edge.viaPolicies = rr.viaPolicies();
                } else if (br.reachesAnyResource()) {
                    // Not specifically named, but the agent holds a broad (wildcard) grant that covers it — so it's
                    // entitled-via-wildcard, not ungoverned drift. The over-breadth shows up as BROAD_GRANT.
                    edge.entitled = true;
                    if (edge.grantStatus == null) edge.grantStatus = "WILDCARD";
                }
                // The blast-radius "used" is ALLOW-based. If the observed pass didn't already record this edge
                // (e.g. an A2A skill edge), seed it from the reach; never downgrade richer allow/deny data.
                if (rr.used()) {
                    edge.observed = true;
                    if (edge.count == 0) {
                        edge.count = rr.useCount();
                        edge.allowed = rr.useCount();
                    }
                    if (edge.lastUsed == null) edge.lastUsed = rr.lastUsed();
                }
            }
        }

        return assemble(nodes, edges, capabilitySensitivity(tenant));
    }

    // ── assembly: derive gap + risk, weight nodes, layer sensitivity, build the DTO ───────────────

    private AccessGraph assemble(Map<String, NodeAcc> nodes, Map<String, EdgeAcc> edges, Map<String, String> capSens) {
        // Tool nodes inherit the peak egress sensitivity classified for their capability.
        for (Map.Entry<String, NodeAcc> en : nodes.entrySet()) {
            NodeAcc n = en.getValue();
            if (!"TOOL".equals(n.type)) continue;
            String sens = capSens.get(capabilityOf(en.getKey()));
            if (sens == null) continue;
            n.dataClass = sens;
            if ("RESTRICTED".equals(sens)) { n.risk = maxRisk(n.risk, "HIGH"); n.flags.add("RESTRICTED_DATA"); }
            else if ("CONFIDENTIAL".equals(sens)) { n.risk = maxRisk(n.risk, "MEDIUM"); n.flags.add("CONFIDENTIAL_DATA"); }
        }

        Map<String, Long> weight = new LinkedHashMap<>();
        List<AccessGraph.Edge> edgeList = new ArrayList<>(edges.size());
        int overPriv = 0, ungoverned = 0, blocked = 0;
        long allowedTotal = 0, deniedTotal = 0;

        for (EdgeAcc e : edges.values()) {
            String gap = gapOf(e);
            String targetSens = "invoked".equals(e.relation) ? capSens.get(capabilityOf(e.target)) : null;
            String risk = edgeRisk(e, gap, targetSens);

            if ("OVER_PRIVILEGE".equals(gap)) overPriv++;
            if ("UNGOVERNED_USE".equals(gap)) ungoverned++;
            if ("BLOCKED".equals(e.grantStatus)) blocked++;
            allowedTotal += e.allowed;
            deniedTotal += e.denied;

            edgeList.add(new AccessGraph.Edge(e.source, e.target, e.relation,
                    e.observed, e.count, e.allowed, e.denied, e.lastUsed,
                    e.entitled, e.grantStatus, e.viaPolicies == null ? List.of() : e.viaPolicies,
                    gap, risk));

            // Node weight = the request leg a node owns (agents own their invoked/a2a call; tools own the
            // landing call; actors own the acts-through they initiate) — avoids double-counting agents.
            if ("invoked".equals(e.relation) || "a2a".equals(e.relation)) {
                weight.merge(e.source, e.count, Long::sum);
                weight.merge(e.target, e.count, Long::sum);
            } else {
                weight.merge(e.source, e.count, Long::sum);
            }

            // Propagate edge signals onto the source node's flags/risk.
            NodeAcc src = nodes.get(e.source);
            if (src != null) {
                if ("OVER_PRIVILEGE".equals(gap)) src.flags.add("OVER_PRIVILEGED");
                if (e.denied > 0 && e.allowed == 0) src.flags.add("PROBING");
                if (e.observed && ("RESTRICTED".equals(targetSens) || "CONFIDENTIAL".equals(targetSens)))
                    src.flags.add("SENSITIVE_REACH");
                src.risk = maxRisk(src.risk, risk);
            }
            NodeAcc tgt = nodes.get(e.target);
            if (tgt != null) tgt.risk = maxRisk(tgt.risk, risk);
        }

        // Accountability: risk flows UP the delegation chain — an actor (or delegating agent) inherits the risk of
        // whatever it delegates to. A human behind a HIGH-risk agent is accountable for that reach, not "low risk".
        // Bounded fixpoint over acts-through + a2a edges (risk only ever rises, so it converges quickly).
        boolean changed = true;
        int guard = nodes.size() + 1;
        while (changed && guard-- > 0) {
            changed = false;
            for (EdgeAcc e : edges.values()) {
                if (!"acts-through".equals(e.relation) && !"a2a".equals(e.relation)) continue;
                NodeAcc up = nodes.get(e.source), down = nodes.get(e.target);
                if (up == null || down == null) continue;
                String bumped = maxRisk(up.risk, down.risk);
                if (!bumped.equals(up.risk)) { up.risk = bumped; changed = true; }
            }
        }

        int humans = 0, nhis = 0, agents = 0, tools = 0, skills = 0, servers = 0;
        List<AccessGraph.Node> nodeList = new ArrayList<>(nodes.size());
        for (Map.Entry<String, NodeAcc> en : nodes.entrySet()) {
            NodeAcc n = en.getValue();
            switch (n.type) {
                case "HUMAN" -> humans++;
                case "NHI" -> nhis++;
                case "AGENT" -> agents++;
                case "TOOL" -> tools++;
                case "SKILL" -> skills++;
                case "SERVER" -> servers++;
                default -> { }
            }
            nodeList.add(new AccessGraph.Node(en.getKey(), n.type, n.label, n.sublabel,
                    weight.getOrDefault(en.getKey(), 0L), n.dataClass, n.risk, new ArrayList<>(n.flags)));
        }

        AccessGraph.Summary summary = new AccessGraph.Summary(humans, nhis, agents, tools, skills, servers,
                overPriv, ungoverned, blocked, allowedTotal, deniedTotal);
        return new AccessGraph(nodeList, edgeList, summary);
    }

    /** Granted-but-never-used = over-privilege; used-but-ungranted = drift. */
    private static String gapOf(EdgeAcc e) {
        boolean grantedLive = e.entitled && ("ACTIVE".equals(e.grantStatus) || "CONDITIONAL".equals(e.grantStatus));
        if (grantedLive && !e.observed) return "OVER_PRIVILEGE";
        // "Used" means an ALLOW actually landed — a denied-only edge is blocked, not ungoverned drift.
        if (e.allowed > 0 && !e.entitled && "invoked".equals(e.relation)) return "UNGOVERNED_USE";
        return null;
    }

    private static String edgeRisk(EdgeAcc e, String gap, String targetSensitivity) {
        String risk;
        if (e.denied > 0 && e.allowed == 0) risk = "HIGH";        // fully denied — probing / misconfig
        else if ("UNGOVERNED_USE".equals(gap)) risk = "HIGH";      // reached with no naming policy — drift
        else if (e.denied > 0) risk = "MEDIUM";                    // mixed allow/deny
        else if ("OVER_PRIVILEGE".equals(gap)) risk = "MEDIUM";    // standing unused grant
        else if (e.observed || (e.entitled && "ACTIVE".equals(e.grantStatus))) risk = "LOW";
        else risk = "NONE";
        // Actually reaching restricted/confidential data raises the floor.
        if (e.observed && "RESTRICTED".equals(targetSensitivity)) risk = maxRisk(risk, "HIGH");
        else if (e.observed && "CONFIDENTIAL".equals(targetSensitivity)) risk = maxRisk(risk, "MEDIUM");
        return risk;
    }

    /** Peak egress sensitivity per capability, from the DLP classification ledger. */
    private Map<String, String> capabilitySensitivity(String tenant) {
        Map<String, String> peak = new LinkedHashMap<>();
        for (Object[] row : classificationRepo.capabilityProfileRows(tenant)) {
            String cap = (String) row[1];
            String sens = (String) row[4];
            if (cap == null || sens == null) continue;
            if (sensRank(sens) > sensRank(peak.get(cap))) peak.put(cap, sens);
        }
        return peak;
    }

    private static int sensRank(String s) {
        return switch (s == null ? "" : s) {
            case "RESTRICTED" -> 3;
            case "CONFIDENTIAL" -> 2;
            case "INTERNAL" -> 1;
            default -> 0; // PUBLIC / unknown
        };
    }

    // ── accumulators + helpers ────────────────────────────────────────────────────────────────────

    private static void node(Map<String, NodeAcc> nodes, String id, String type, String label, String sublabel) {
        nodes.computeIfAbsent(id, k -> new NodeAcc(type, label, sublabel));
    }

    private static void observedEdge(Map<String, EdgeAcc> edges, String source, String relation, String target,
                                     long count, long allowed, long denied, LocalDateTime lastUsed) {
        EdgeAcc e = edges.computeIfAbsent(edgeKey(source, relation, target),
                k -> new EdgeAcc(source, relation, target));
        e.observed = true;
        e.count += count;
        e.allowed += allowed;
        e.denied += denied;
        if (lastUsed != null && (e.lastUsed == null || lastUsed.isAfter(e.lastUsed))) e.lastUsed = lastUsed;
    }

    private static String edgeKey(String source, String relation, String target) {
        return source + "|" + relation + "|" + target;
    }

    private static String capabilityOf(String toolNodeId) {
        return toolNodeId.startsWith("tool:") ? toolNodeId.substring(5) : toolNodeId;
    }

    private static final List<String> RISK_ORDER = List.of("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL");

    private static String maxRisk(String a, String b) {
        return RISK_ORDER.indexOf(a) >= RISK_ORDER.indexOf(b) ? a : b;
    }

    private static final class NodeAcc {
        final String type;
        final String label;
        final String sublabel;
        final Set<String> flags = new LinkedHashSet<>();
        String risk = "NONE";
        String dataClass;

        NodeAcc(String type, String label, String sublabel) {
            this.type = type;
            this.label = label;
            this.sublabel = sublabel;
        }
    }

    private static final class EdgeAcc {
        final String source;
        final String relation;
        final String target;
        boolean observed;
        long count, allowed, denied;
        LocalDateTime lastUsed;
        boolean entitled;
        String grantStatus;
        List<String> viaPolicies;

        EdgeAcc(String source, String relation, String target) {
            this.source = source;
            this.relation = relation;
            this.target = target;
        }
    }
}
