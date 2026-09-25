package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayHumanUserRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport.AgentRow;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport.PolicyOwnership;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport.Summary;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentAccountability;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentAccountability.AccountableHuman;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentAccountability.DelegationPath;
import com.ws.wsAgenticSecurityGateway.ciso.dto.HumanAccountability;
import com.ws.wsAgenticSecurityGateway.ciso.dto.HumanAccountability.AgentLink;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayPolicyRepository;
import com.ws.wsAgenticSecurityGateway.sts.model.Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Human accountability, derived from the OBO delegation lineage ({@code actChain}) that the PDP records on every
 * governed evaluation. The chain's first element is the verified root (a human, for OBO calls); its last hop is
 * the acting agent ({@code pdp_subject}); its length is the delegation depth. Depth 2 = the human drove the agent
 * directly (DIRECT); depth ≥ 3 = the agent was reached through other agents (DELEGATED).
 *
 * <p>No owner-of-record is invented — that is a future platform-sync concern. Policy ownership uses the real
 * {@code created_by} author. Read-only, tenant-scoped.
 */
@Service
@Slf4j
public class AccountabilityService {

    /** Evaluation-scan cap (whole tenant). Generous vs. real ledgers; the summary notes if it is hit. */
    private static final int MAX_EVALS = 20_000;
    /** Cap on distinct delegation lineages returned in an agent detail. */
    private static final int MAX_PATHS = 25;

    private static final String DIRECT = "DIRECT";
    private static final String DELEGATED = "DELEGATED";
    private static final String MIXED = "MIXED";
    private static final String UNROOTED = "UNROOTED";

    private final PdpAuditLogRepository pdpRepo;
    private final GatewayAgentRepository agentRepo;
    private final GatewayHumanUserRepository humanRepo;
    private final GatewayPolicyRepository policyRepo;
    private final ObjectMapper objectMapper;

    public AccountabilityService(PdpAuditLogRepository pdpRepo, GatewayAgentRepository agentRepo,
                                 GatewayHumanUserRepository humanRepo, GatewayPolicyRepository policyRepo,
                                 ObjectMapper objectMapper) {
        this.pdpRepo = pdpRepo;
        this.agentRepo = agentRepo;
        this.humanRepo = humanRepo;
        this.policyRepo = policyRepo;
        this.objectMapper = objectMapper;
    }

    // ── Tenant roll-up ───────────────────────────────────────────────────────────

    public AccountabilityReport getReport() {
        String tenant = TenantContext.get();
        List<Eval> evals = classifyAll(pdpRepo.accountabilityEvaluations(tenant, MAX_EVALS));
        Map<String, GatewayHumanUserEntity> humans = humansByIdpSubject(tenant);
        Map<String, String> approvalByAgent = approvalByAgent(tenant);

        Map<String, long[]> agentCounts = new LinkedHashMap<>();          // agent -> [gov,direct,deleg,unrooted,maxDepth,verifiedRoot]
        Map<String, Map<String, Long>> agentHumanReq = new HashMap<>();   // agent -> humanId -> count
        Map<String, Principal> rootById = new HashMap<>();                // humanId -> root principal
        Map<String, long[]> humanTotal = new LinkedHashMap<>();           // humanId -> [total]
        Map<String, Map<String, int[]>> humanAgentAttr = new HashMap<>(); // humanId -> agent -> [direct,delegated]

        for (Eval e : evals) {
            long[] ac = agentCounts.computeIfAbsent(e.agent, k -> new long[6]);
            ac[0]++;
            if (DIRECT.equals(e.attribution)) ac[1]++;
            else if (DELEGATED.equals(e.attribution)) ac[2]++;
            if (!e.humanRoot) ac[3]++;
            ac[4] = Math.max(ac[4], e.depth);
            if (e.humanRoot && e.root.verified()) ac[5] = 1;

            if (e.humanRoot) {
                String hid = e.root.id();
                rootById.putIfAbsent(hid, e.root);
                agentHumanReq.computeIfAbsent(e.agent, k -> new HashMap<>()).merge(hid, 1L, Long::sum);
                humanTotal.computeIfAbsent(hid, k -> new long[1])[0]++;
                int[] attr = humanAgentAttr.computeIfAbsent(hid, k -> new HashMap<>())
                        .computeIfAbsent(e.agent, k -> new int[2]);
                if (DIRECT.equals(e.attribution)) attr[0]++; else attr[1]++;
            }
        }

        List<AgentRow> agentRows = new ArrayList<>();
        for (Map.Entry<String, long[]> en : agentCounts.entrySet()) {
            String agent = en.getKey();
            long[] ac = en.getValue();
            Map<String, Long> hr = agentHumanReq.getOrDefault(agent, Map.of());
            String primary = hr.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(h -> display(rootById.get(h.getKey()), humans)).orElse(null);
            boolean hasVerifiedHumanRoot = ac[5] == 1;   // a HUMAN root that was strongly authenticated
            agentRows.add(new AgentRow(agent, approvalByAgent.get(agent), ac[0], hasVerifiedHumanRoot,
                    ac[1], ac[2], ac[3], (int) ac[4], hr.size(), primary));
        }
        agentRows.sort(Comparator.comparingLong(AgentRow::governedRequests).reversed());

        List<HumanAccountability> humanRows = new ArrayList<>();
        for (Map.Entry<String, long[]> en : humanTotal.entrySet()) {
            String hid = en.getKey();
            Principal root = rootById.get(hid);
            GatewayHumanUserEntity h = humans.get(hid);
            List<AgentLink> links = new ArrayList<>();
            int directAgents = 0, delegatedAgents = 0;
            for (Map.Entry<String, int[]> ae : humanAgentAttr.getOrDefault(hid, Map.of()).entrySet()) {
                int[] attr = ae.getValue();
                String a = attr[0] > 0 && attr[1] > 0 ? MIXED : attr[0] > 0 ? DIRECT : DELEGATED;
                links.add(new AgentLink(ae.getKey(), attr[0] + attr[1], a));
                if (attr[0] > 0) directAgents++;
                else if (attr[1] > 0) delegatedAgents++;
            }
            links.sort(Comparator.comparingLong(AgentLink::requests).reversed());
            humanRows.add(new HumanAccountability(hid, username(root, h),
                    h != null ? h.getFullName() : null, h != null ? h.getEmail() : null,
                    h != null ? h.getStatus() : "UNREGISTERED", root.verified(),
                    en.getValue()[0], directAgents, delegatedAgents, links));
        }
        humanRows.sort(Comparator.comparingLong(HumanAccountability::totalRequests).reversed());

        int agentsWithHumanRoot = (int) agentRows.stream().filter(AgentRow::hasVerifiedHumanRoot).count();
        Summary summary = new Summary(
                approvalByAgent.size(), agentCounts.size(), agentsWithHumanRoot,
                agentCounts.size() - agentsWithHumanRoot, humanTotal.size(),
                evals.size(), count(evals, DIRECT), count(evals, DELEGATED), countUnrooted(evals));

        return new AccountabilityReport(tenant, LocalDateTime.now(), summary, agentRows, humanRows,
                policyOwnership(tenant), reportNotes(evals.size()));
    }

    // ── Per-agent detail ─────────────────────────────────────────────────────────

    public AgentAccountability getAgentAccountability(String agentName) {
        String tenant = TenantContext.get();
        List<Eval> evals = classifyAll(pdpRepo.accountabilityEvaluationsForAgent(tenant, agentName, MAX_EVALS));
        Map<String, GatewayHumanUserEntity> humans = humansByIdpSubject(tenant);

        Map<String, long[]> perHuman = new LinkedHashMap<>();  // humanId -> [total,direct,delegated]
        Map<String, Principal> rootById = new HashMap<>();
        Map<List<String>, long[]> perPath = new LinkedHashMap<>();
        Map<List<String>, String> pathAttr = new HashMap<>();
        boolean verifiedRoot = false;
        int maxDepth = 0;

        for (Eval e : evals) {
            maxDepth = Math.max(maxDepth, e.depth);
            if (e.humanRoot) {
                if (e.root.verified()) verifiedRoot = true;
                String hid = e.root.id();
                rootById.putIfAbsent(hid, e.root);
                long[] agg = perHuman.computeIfAbsent(hid, k -> new long[3]);
                agg[0]++;
                if (DIRECT.equals(e.attribution)) agg[1]++; else if (DELEGATED.equals(e.attribution)) agg[2]++;
            }
            if (!e.path.isEmpty()) {
                perPath.computeIfAbsent(e.path, k -> new long[1])[0]++;
                pathAttr.putIfAbsent(e.path, e.attribution == null ? UNROOTED : e.attribution);
            }
        }

        List<AccountableHuman> accountable = new ArrayList<>();
        for (Map.Entry<String, long[]> en : perHuman.entrySet()) {
            accountable.add(toAccountableHuman(rootById.get(en.getKey()), humans.get(en.getKey()), en.getValue()));
        }
        accountable.sort(Comparator.comparingLong(AccountableHuman::requests).reversed());

        List<DelegationPath> paths = new ArrayList<>();
        for (Map.Entry<List<String>, long[]> en : perPath.entrySet()) {
            paths.add(new DelegationPath(en.getKey(), pathAttr.get(en.getKey()), en.getValue()[0]));
        }
        paths.sort(Comparator.comparingLong(DelegationPath::requests).reversed());
        if (paths.size() > MAX_PATHS) paths = paths.subList(0, MAX_PATHS);

        return new AgentAccountability(
                agentName, approvalByAgent(tenant).get(agentName), LocalDateTime.now(),
                evals.size(), verifiedRoot,
                count(evals, DIRECT), count(evals, DELEGATED), countUnrooted(evals), maxDepth,
                accountable.isEmpty() ? null : accountable.get(0), accountable, paths,
                agentNotes(countUnrooted(evals)));
    }

    // ── Classification ───────────────────────────────────────────────────────────

    /** Parsed, classified evaluation: acting agent, root principal, depth-derived attribution, display path. */
    private record Eval(LocalDateTime at, String agent, Principal root, boolean humanRoot, int depth,
                        String attribution, List<String> path) {}

    private List<Eval> classifyAll(List<Object[]> rows) {
        List<Eval> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) out.add(classify(r));
        return out;
    }

    private Eval classify(Object[] r) {
        LocalDateTime at = toDateTime(r[0]);
        String agent = r[1] == null ? "unknown" : (String) r[1];
        List<Principal> chain = parseChain((String) r[6]);
        Principal root = chain.isEmpty() ? null : chain.get(0);
        // Accountability requires a VERIFIED human root. An unverified/unknown root (the {@code unknownRoot}
        // sentinel, type=human but verified=false) is NOT a real answerable human — count it as unrooted.
        boolean humanRoot = root != null && root.type() == Principal.PrincipalType.HUMAN && root.verified();
        int depth = chain.size();
        String attribution = humanRoot ? (depth <= 2 ? DIRECT : DELEGATED) : null;

        List<String> path = new ArrayList<>(chain.size());
        for (Principal p : chain) path.add(display(p));
        // The acting agent (pdp_subject) is the chain's last hop — show its resolved name, not a raw UUID.
        if (path.size() >= 2 && !agent.isBlank()) path.set(path.size() - 1, agent);

        return new Eval(at, agent, root, humanRoot, depth, attribution, List.copyOf(path));
    }

    private List<Principal> parseChain(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            List<Principal> out = new ArrayList<>(raw.size());
            for (Map<String, Object> m : raw) {
                Principal p = Principal.fromClaim(m);
                if (p != null) out.add(p);
            }
            return out;
        } catch (Exception ex) {
            log.warn("Accountability: failed to parse actChain ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private AccountableHuman toAccountableHuman(Principal root, GatewayHumanUserEntity h, long[] agg) {
        return new AccountableHuman(root.id(), username(root, h),
                h != null ? h.getFullName() : null, h != null ? h.getEmail() : null,
                h != null ? h.getStatus() : "UNREGISTERED", root.verified(), agg[0], agg[1], agg[2]);
    }

    private PolicyOwnership policyOwnership(String tenant) {
        List<GatewayPolicyEntity> policies = policyRepo.findAllByWsTenantName(tenant);
        Map<String, Long> byOwner = new LinkedHashMap<>();
        int unowned = 0;
        for (GatewayPolicyEntity p : policies) {
            String owner = p.getCreatedBy();
            if (owner == null || owner.isBlank()) {
                unowned++;
                owner = "unassigned";
            }
            byOwner.merge(owner, 1L, Long::sum);
        }
        return new PolicyOwnership(policies.size(), byOwner, unowned);
    }

    private Map<String, GatewayHumanUserEntity> humansByIdpSubject(String tenant) {
        Map<String, GatewayHumanUserEntity> map = new LinkedHashMap<>();
        for (GatewayHumanUserEntity h : humanRepo.findAllByWsTenantName(tenant)) {
            if (h.getIdpSubject() != null) map.putIfAbsent(h.getIdpSubject(), h);
        }
        return map;
    }

    private Map<String, String> approvalByAgent(String tenant) {
        Map<String, String> map = new LinkedHashMap<>();
        for (GatewayAgentEntity a : agentRepo.findAllByWsTenantName(tenant)) {
            if (a.getAgentName() != null) map.putIfAbsent(a.getAgentName(), a.getApprovalStatus());
        }
        return map;
    }

    private static String display(Principal p) {
        if (p == null) return "?";
        if (p.type() == Principal.PrincipalType.HUMAN) {
            return p.username() != null ? p.username() : p.id();
        }
        return p.workloadId() != null ? p.workloadId() : (p.username() != null ? p.username() : p.id());
    }

    private static String display(Principal root, Map<String, GatewayHumanUserEntity> humans) {
        if (root == null) return null;
        GatewayHumanUserEntity h = humans.get(root.id());
        return username(root, h);
    }

    private static String username(Principal root, GatewayHumanUserEntity h) {
        if (root != null && root.username() != null) return root.username();
        if (h != null && h.getPreferredUsername() != null) return h.getPreferredUsername();
        return root != null ? root.id() : null;
    }

    private static long count(List<Eval> evals, String attribution) {
        return evals.stream().filter(e -> attribution.equals(e.attribution)).count();
    }

    private static long countUnrooted(List<Eval> evals) {
        return evals.stream().filter(e -> !e.humanRoot).count();
    }

    private static LocalDateTime toDateTime(Object o) {
        if (o instanceof LocalDateTime ldt) return ldt;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }

    private static List<String> reportNotes(int evalCount) {
        List<String> notes = new ArrayList<>();
        notes.add("Accountability is derived from the on-behalf-of delegation chain recorded with every "
                + "authorization decision; the root of the chain is the verified human, and its depth distinguishes "
                + "DIRECT (a human drove the agent) from DELEGATED (reached through other agents).");
        notes.add("Policy ownership is the policy's recorded author. An assigned owner-of-record for each agent "
                + "(from platform onboarding) is intentionally not shown — accountability already resolves the "
                + "answerable human.");
        if (evalCount >= MAX_EVALS) {
            notes.add("Analysis capped at the " + MAX_EVALS + " most recent decisions; counts reflect that window.");
        }
        return notes;
    }

    private static List<String> agentNotes(long unrooted) {
        List<String> notes = new ArrayList<>();
        notes.add("DIRECT = a human drove this agent (human → agent); DELEGATED = it was reached through other "
                + "agents (human → … → agent), taken from the on-behalf-of delegation chain on each decision.");
        if (unrooted > 0) {
            notes.add(unrooted + " request(s) had no verified human at the root of the delegation chain (for "
                    + "example a system-initiated call); these are excluded from the answerable-human totals.");
        }
        return notes;
    }
}
