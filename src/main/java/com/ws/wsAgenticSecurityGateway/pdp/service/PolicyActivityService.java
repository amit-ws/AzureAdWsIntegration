package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyActivityReport;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyActivityReport.PolicyActivity;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyActivityReport.Summary;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the CISO Policy-Activity report by merging the policy catalog ({@code gateway_policy}) with the decision
 * ledger ({@code pdp_audit_log}). Every number is real: per-policy allow/deny/last-fired come from the ledger
 * (multi-policy decisions split so each contributing policy is counted), and a "dead" policy is simply a catalog
 * entry the ledger never attributed a decision to. Read-only — this never participates in a decision.
 */
@Service
@Slf4j
public class PolicyActivityService {

    private final PdpAuditLogRepository pdpRepo;
    private final PolicyService policyService;

    public PolicyActivityService(PdpAuditLogRepository pdpRepo, PolicyService policyService) {
        this.pdpRepo = pdpRepo;
        this.policyService = policyService;
    }

    /** Per-policy activity + tenant coverage for the caller's tenant. */
    public PolicyActivityReport getPolicyActivity() {
        String tenant = TenantContext.get();

        // Ledger aggregates, keyed by cedar policy id.
        Map<String, long[]> counts = new HashMap<>();          // id -> [evaluations, allows, denies]
        Map<String, LocalDateTime> lastFired = new HashMap<>();
        for (Object[] row : pdpRepo.aggregatePolicyActivity(tenant)) {
            String id = (String) row[0];
            counts.put(id, new long[]{ asLong(row[1]), asLong(row[2]), asLong(row[3]) });
            lastFired.put(id, asDateTime(row[4]));
        }

        // Merge against the full catalog so policies with zero activity surface as "dead".
        List<GatewayPolicyEntity> policies = policyService.getAllPolicies(); // tenant-scoped
        List<PolicyActivity> items = new ArrayList<>(policies.size());
        for (GatewayPolicyEntity p : policies) {
            long[] c = counts.get(p.getCedarPolicyId());
            boolean dead = (c == null);
            items.add(new PolicyActivity(
                    p.getPolicyName(),
                    p.getCedarPolicyId(),
                    p.getEffect(),
                    Boolean.TRUE.equals(p.getEnabled()),
                    dead ? 0L : c[0],
                    dead ? 0L : c[1],
                    dead ? 0L : c[2],
                    dead ? null : lastFired.get(p.getCedarPolicyId()),
                    dead));
        }
        // Most-active first; dead ones sink to the bottom; ties broken by name for a stable order.
        items.sort(Comparator
                .comparingLong(PolicyActivity::evaluations).reversed()
                .thenComparing(pa -> pa.policyName() == null ? "" : pa.policyName()));

        int deadCount = (int) items.stream().filter(PolicyActivity::dead).count();

        long[] cov = coverage(tenant);
        Summary summary = new Summary(
                policies.size(),
                policies.size() - deadCount,
                deadCount,
                cov[0], cov[1], cov[2], cov[3], cov[4]);

        return new PolicyActivityReport(items, summary);
    }

    private long[] coverage(String tenant) {
        List<Object[]> rows = pdpRepo.policyDecisionCoverage(tenant);
        if (rows == null || rows.isEmpty()) {
            return new long[]{0, 0, 0, 0, 0};
        }
        Object[] r = rows.get(0);
        return new long[]{ asLong(r[0]), asLong(r[1]), asLong(r[2]), asLong(r[3]), asLong(r[4]) };
    }

    /** Native COUNT/aggregates come back as Long or BigInteger depending on the driver — normalise defensively. */
    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    /** MAX(timestamp) arrives as {@link java.sql.Timestamp} (native) or {@link LocalDateTime} — accept either. */
    private static LocalDateTime asDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime ldt) return ldt;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }
}
