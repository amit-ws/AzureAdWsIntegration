package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius.ReachSummary;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius.ResourceReach;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine.PolicyResource;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes an agent's blast radius — its attack surface — for the CISO dashboard.
 *
 * <p>Two sources, deliberately layered:
 * <ol>
 *   <li><b>Actual reach</b> (audit ledger, {@code pdp_subject → pdp_resource}) — the ground-truth, complete and
 *       clean, of what the agent has really reached.</li>
 *   <li><b>Policy grants</b> — the policies applying to the agent: those that name it, tenant-wide guardrails,
 *       <b>and those observed deciding for it in the audit</b> (this is how GROUP-scoped grants — invisible to the
 *       per-agent principal read-model — are recovered: if a group policy ever decided for the agent, the agent
 *       is in that group). Each grant is classified ACTIVE / LATENT / CONDITIONAL / BLOCKED so nothing is
 *       overstated (a disabled policy is latent, not gone; a {@code when}-conditional policy is conditional).</li>
 * </ol>
 * Read-only; never participates in a decision.
 */
@Service
@Slf4j
public class BlastRadiusService {

    private final PolicyService policyService;
    private final CedarPolicyEngine cedarEngine;
    private final PdpAuditLogRepository pdpRepo;

    public BlastRadiusService(PolicyService policyService, CedarPolicyEngine cedarEngine,
                              PdpAuditLogRepository pdpRepo) {
        this.policyService = policyService;
        this.cedarEngine = cedarEngine;
        this.pdpRepo = pdpRepo;
    }

    public AgentBlastRadius getAgentBlastRadius(String agentName) {
        String tenant = TenantContext.get();

        // Policies applying to this agent: named directly, tenant-wide (ANY/AGENT_TYPE), or observed deciding for
        // it in the audit (recovers GROUP grants). One small catalog read + a set membership test — policies are few.
        Set<String> observed = new HashSet<>(pdpRepo.policyIdsThatDecidedFor(tenant, agentName));
        List<GatewayPolicyEntity> applicable = policyService.getAllPolicies().stream()
                .filter(p -> agentName.equals(p.getPrincipalId())
                        || "ANY".equals(p.getPrincipalKind())
                        || "AGENT_TYPE".equals(p.getPrincipalKind())
                        || observed.contains(p.getCedarPolicyId()))
                .toList();

        // Accumulate policy status per named resource, and track wildcard (bare-resource) grants separately.
        Map<ResKey, Acc> byResource = new LinkedHashMap<>();
        boolean reachesAny = false;
        Set<String> wildcardGrants = new LinkedHashSet<>();
        for (GatewayPolicyEntity p : applicable) {
            boolean permit = "PERMIT".equalsIgnoreCase(p.getEffect());
            boolean enabled = Boolean.TRUE.equals(p.getEnabled());
            boolean conditional = cedarEngine.hasConditions(p.getPolicyText());
            for (PolicyResource r : cedarEngine.extractResources(p.getPolicyText())) {
                if ("ANY".equals(r.kind())) {
                    // A bare-resource grant = "can reach anything". Only an enabled PERMIT actually widens surface.
                    if (permit && enabled) {
                        reachesAny = true;
                        wildcardGrants.add(p.getPolicyName() + (conditional ? " (conditional)" : ""));
                    }
                    continue;
                }
                Acc acc = byResource.computeIfAbsent(new ResKey(r.kind(), r.id()), k -> new Acc());
                acc.via.add(p.getPolicyName());
                if (!permit) {
                    if (enabled) acc.activeForbid = true; else acc.latentForbid = true;
                } else if (!enabled) {
                    acc.latentPermit = true;        // a disabled grant is dormant — latent — whether or not conditional
                } else if (conditional) {
                    acc.conditionalPermit = true;   // enabled + conditional
                } else {
                    acc.activePermit = true;        // enabled + unconditional
                }
            }
        }

        // Actual reach from the audit ledger (ground truth), keyed by resource id.
        Map<String, Long> useCount = new HashMap<>();
        Map<String, LocalDateTime> lastUsed = new HashMap<>();
        Map<String, String> usedKind = new HashMap<>();
        for (Object[] row : pdpRepo.actualReachBySubject(tenant, agentName)) {
            String id = (String) row[0];
            useCount.put(id, asLong(row[2]));
            lastUsed.put(id, asDateTime(row[3]));
            usedKind.put(id, kindForAction((String) row[1]));
        }

        List<ResourceReach> reach = new ArrayList<>();
        Set<String> consumedUsedIds = new HashSet<>();
        for (Map.Entry<ResKey, Acc> e : byResource.entrySet()) {
            ResKey k = e.getKey();
            Acc a = e.getValue();
            String status = a.status();               // null ⇒ only a dormant forbid names it
            boolean used = useCount.containsKey(k.id());
            if (status == null) {
                if (!used) continue;                   // a disabled forbid on an ungranted, unused resource — skip
                status = "USED_ONLY";
            }
            reach.add(reachOf(k.kind(), k.id(), status, new ArrayList<>(a.via), used, useCount, lastUsed));
            consumedUsedIds.add(k.id());
        }
        // Resources actually reached but not specifically named by any applicable policy (wildcard grant / drift).
        for (String id : useCount.keySet()) {
            if (!consumedUsedIds.contains(id)) {
                reach.add(reachOf(usedKind.getOrDefault(id, "UNKNOWN"), id, "USED_ONLY", List.of(),
                        true, useCount, lastUsed));
            }
        }
        // Most-used first, then id — surfaces the live surface at the top.
        reach.sort(Comparator.comparingLong(ResourceReach::useCount).reversed()
                .thenComparing(ResourceReach::resourceId));

        ReachSummary summary = summarize(reach);
        List<String> notes = notes(reachesAny, wildcardGrants);
        return new AgentBlastRadius(agentName, reachesAny, reach, summary, notes);
    }

    private static ResourceReach reachOf(String kind, String id, String status, List<String> via,
                                         boolean used, Map<String, Long> useCount, Map<String, LocalDateTime> lastUsed) {
        return new ResourceReach(kind, id, status, via,
                used, used ? useCount.get(id) : 0L, used ? lastUsed.get(id) : null);
    }

    private static ReachSummary summarize(List<ResourceReach> reach) {
        int active = 0, latent = 0, conditional = 0, blocked = 0, used = 0;
        for (ResourceReach r : reach) {
            switch (r.status()) {
                case "ACTIVE" -> active++;
                case "LATENT" -> latent++;
                case "CONDITIONAL" -> conditional++;
                case "BLOCKED" -> blocked++;
                default -> { /* USED_ONLY — counted only via used below */ }
            }
            if (r.used()) used++;
        }
        return new ReachSummary(active, latent, conditional, blocked, used);
    }

    private static List<String> notes(boolean reachesAny, Set<String> wildcardGrants) {
        List<String> notes = new ArrayList<>();
        if (reachesAny) {
            notes.add("Has a broad (wildcard) grant — can reach resources beyond those listed"
                    + (wildcardGrants.isEmpty() ? "" : " (via " + String.join(", ", wildcardGrants) + ")") + ".");
        }
        notes.add("Group-scoped grants are inferred from observed audit decisions; a group the agent belongs to "
                + "but has never exercised will not appear here.");
        return notes;
    }

    private static String kindForAction(String action) {
        if ("toolCall".equals(action)) return "TOOL";
        if ("skillInvocation".equals(action)) return "SKILL";
        return action == null ? "UNKNOWN" : action;
    }

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    private static LocalDateTime asDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime ldt) return ldt;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }

    /** A resource identity for de-duplication across policies. */
    private record ResKey(String kind, String id) {}

    /** Mutable per-resource accumulator of the policy flags seen across all applicable policies. */
    private static final class Acc {
        final Set<String> via = new LinkedHashSet<>();
        boolean activePermit, latentPermit, conditionalPermit, activeForbid, latentForbid;

        /** Resolve the display status by precedence: active block wins, then active grant, then conditional, then latent. */
        String status() {
            if (activeForbid) return "BLOCKED";
            if (activePermit) return "ACTIVE";
            if (conditionalPermit) return "CONDITIONAL";
            if (latentPermit) return "LATENT";
            return null; // only a dormant (disabled) forbid names it — not reachable, not an active block
        }
    }
}
