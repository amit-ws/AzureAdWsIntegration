package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayHumanUserRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PostureReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PostureReport.PostureCheck;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayPolicyRepository;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsKeyEntity;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsRotationPolicyEntity;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsKeyRepository;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsRevocationRepository;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsRotationPolicyRepository;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsSessionRevocationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds the {@link PostureReport} — a default-deny-correct security scorecard. Governing principle: an agent with
 * no permit cannot act, so broad ENABLED permits are the risk, not disabled/missing ones. Breadth is measured from
 * the policy <em>head</em> (wildcard principal/action/resource); a {@code when}-condition only mitigates. Every
 * check reads real data. Scored checks weigh 100 total; observability signals are informational (weight 0).
 */
@Service
@Slf4j
public class PostureService {

    private static final String GOOD = "GOOD";
    private static final String WARN = "WARN";
    private static final String CRITICAL = "CRITICAL";
    private static final String INFO = "INFO";

    /** A permit whose action/resource is constrained (vs a bare {@code action,} / {@code resource} wildcard). */
    private static final Pattern ACTION_SCOPED = Pattern.compile("action\\s*(==|\\bin\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOURCE_SCOPED =
            Pattern.compile("resource\\s*(==|\\bin\\b|\\bis\\b)", Pattern.CASE_INSENSITIVE);

    private final GatewayPolicyRepository policyRepo;
    private final CedarPolicyEngine cedar;
    private final PdpAuditLogRepository pdpRepo;
    private final AccountabilityService accountabilityService;
    private final GatewayStsKeyRepository stsKeyRepo;
    private final GatewayStsRotationPolicyRepository rotationRepo;
    private final GatewayStsRevocationRepository revRepo;
    private final GatewayStsSessionRevocationRepository sessRevRepo;
    private final GatewayHumanUserRepository humanRepo;
    private final GatewayAgentRepository agentRepo;

    public PostureService(GatewayPolicyRepository policyRepo, CedarPolicyEngine cedar,
                          PdpAuditLogRepository pdpRepo, AccountabilityService accountabilityService,
                          GatewayStsKeyRepository stsKeyRepo, GatewayStsRotationPolicyRepository rotationRepo,
                          GatewayStsRevocationRepository revRepo, GatewayStsSessionRevocationRepository sessRevRepo,
                          GatewayHumanUserRepository humanRepo, GatewayAgentRepository agentRepo) {
        this.policyRepo = policyRepo;
        this.cedar = cedar;
        this.pdpRepo = pdpRepo;
        this.accountabilityService = accountabilityService;
        this.stsKeyRepo = stsKeyRepo;
        this.rotationRepo = rotationRepo;
        this.revRepo = revRepo;
        this.sessRevRepo = sessRevRepo;
        this.humanRepo = humanRepo;
        this.agentRepo = agentRepo;
    }

    public PostureReport getReport() {
        String tenant = TenantContext.get();
        List<GatewayPolicyEntity> policies = policyRepo.findAllByWsTenantName(tenant);
        AccountabilityReport acct = accountabilityService.getReport();

        List<PostureCheck> checks = new ArrayList<>();
        checks.add(checkBroadPermits(policies));   // 40 — the ⭐ risk in a default-deny gateway
        checks.add(checkKeyRotation(tenant));      // 25
        checks.add(checkUnaccountable(acct));      // 20
        checks.add(checkGuardrails(policies));     // 15 — defense-in-depth (low sev)
        checks.add(checkEnforcement(tenant));      // informational
        checks.add(checkRevocations(tenant));      // informational

        int score = checks.stream().mapToInt(PostureCheck::pointsEarned).sum();
        String grade = grade(score);
        int critical = (int) checks.stream().filter(c -> CRITICAL.equals(c.status())).count();
        int warn = (int) checks.stream().filter(c -> WARN.equals(c.status())).count();
        int good = (int) checks.stream().filter(c -> GOOD.equals(c.status())).count();
        String descriptor = critical > 0 ? "needs attention"
                : "A".equals(grade) || "B".equals(grade) ? "tight"
                : "C".equals(grade) ? "some gaps to tighten" : "needs attention";
        String headline = critical + " critical, " + warn + " warning" + (warn == 1 ? "" : "s") + " — " + descriptor;

        return new PostureReport(tenant, LocalDateTime.now(), score, grade, headline,
                critical, warn, good, checks, notes(tenant, acct));
    }

    // ── Checks ───────────────────────────────────────────────────────────────────

    /**
     * ⭐ The attack surface of a default-deny gateway is its ENABLED permits — how broad each grant's <em>head</em>
     * is (wildcard principal / action / resource). A {@code when}-condition mitigates but does not scope, so a
     * fully head-scoped permit is GOOD even without one, and an any-principal/all-actions/all-resources permit with
     * no condition is CRITICAL. Disabled permits are ignored — they grant nothing.
     */
    private PostureCheck checkBroadPermits(List<GatewayPolicyEntity> policies) {
        List<GatewayPolicyEntity> enabledPermits = policies.stream()
                .filter(p -> Boolean.TRUE.equals(p.getEnabled()) && "PERMIT".equalsIgnoreCase(p.getEffect()))
                .toList();
        int broad = 0, critical = 0;
        for (GatewayPolicyEntity p : enabledPermits) {
            String text = p.getPolicyText() == null ? "" : p.getPolicyText().replaceAll("//[^\n]*", "");
            boolean principalBroad = "ANY".equalsIgnoreCase(p.getPrincipalKind())
                    || "AGENT_TYPE".equalsIgnoreCase(p.getPrincipalKind());
            boolean actionBroad = !ACTION_SCOPED.matcher(text).find();
            boolean resourceBroad = !RESOURCE_SCOPED.matcher(text).find();
            boolean conditioned = cedar.hasConditions(p.getPolicyText());
            int axes = (principalBroad ? 1 : 0) + (actionBroad ? 1 : 0) + (resourceBroad ? 1 : 0);
            if (axes < 2 && !principalBroad) continue;   // scoped enough — not a broad grant
            broad++;
            if (principalBroad && actionBroad && resourceBroad && !conditioned) critical++;   // everything, unguarded
        }
        String status = critical > 0 ? CRITICAL : broad > 0 ? WARN : GOOD;
        String detail = enabledPermits.size() + " enabled permit(s); " + broad
                + " broad (wildcard action/resource or any-principal), " + critical + " unguarded-broad";
        String why = GOOD.equals(status)
                ? "Every enabled permit is scoped by principal, action and resource — tight."
                : CRITICAL.equals(status)
                ? "An enabled permit grants any principal all actions on all resources with no condition — scope it."
                : "Some enabled permits are broad (wildcard action/resource, or any-principal); a condition mitigates "
                + "but does not scope — review and tighten them.";
        return check("broad_enabled_permits", "Broad enabled permits", status, "config", detail, why, 40);
    }

    /** Signing-key hygiene: an ACTIVE key must exist, rotate automatically, and not be stale. */
    private PostureCheck checkKeyRotation(String tenant) {
        Optional<GatewayStsKeyEntity> active = stsKeyRepo.findFirstByWsTenantNameAndStatus(tenant, "ACTIVE");
        if (active.isEmpty()) {
            return check("key_rotation", "Key rotation", CRITICAL, "config", "no ACTIVE signing key",
                    "No active signing key — token minting/verification is at risk.", 25);
        }
        Optional<GatewayStsRotationPolicyEntity> rot = rotationRepo.findByWsTenantName(tenant);
        boolean auto = rot.map(GatewayStsRotationPolicyEntity::isAutoRotate).orElse(false);
        int interval = rot.map(GatewayStsRotationPolicyEntity::getIntervalDays).orElse(0);
        long ageDays = active.get().getCreatedAt() == null ? 0
                : ChronoUnit.DAYS.between(active.get().getCreatedAt().toLocalDate(), LocalDate.now());
        // Tolerate normal cadence: stale only past 2× the interval, and never below a 7-day floor.
        long staleAfter = Math.max((long) interval * 2, 7);
        boolean stale = interval > 0 && ageDays > staleAfter;
        String status = auto && !stale ? GOOD : WARN;
        String detail = "active key age " + ageDays + "d; auto-rotate " + (auto ? "on" : "off")
                + (interval > 0 ? ", interval " + interval + "d" : "");
        String why = auto && !stale ? "Signing keys rotate automatically and the active key is fresh."
                : !auto ? "Auto-rotation is off — schedule signing-key rotation."
                : "The active key is older than the rotation window — rotate it.";
        return check("key_rotation", "Key rotation", status, "config", detail, why, 25);
    }

    /** Reuses Stage-6 accountability: governed actions with no verified human root at the OBO chain root. */
    private PostureCheck checkUnaccountable(AccountabilityReport acct) {
        long unrooted = acct.summary().unrootedRequests();
        long governed = acct.summary().governedRequests();
        String status = unrooted == 0 ? GOOD : WARN;   // still default-denied where applicable → WARN, not critical
        String detail = unrooted + " of " + governed + " governed actions had no verified human root";
        String why = unrooted == 0 ? "Every governed action traces to a verified human."
                : "Some actions lack a verified human root — review (may be legacy/system callers).";
        return check("unaccountable_actions", "Unaccountable actions", status, "observed", detail, why, 20);
    }

    /** Baseline deny-unverified forbids — defense-in-depth. Disabled is low-severity (a permit must still allow). */
    private PostureCheck checkGuardrails(List<GatewayPolicyEntity> policies) {
        long present = policies.stream().filter(PostureService::isGuardrail).count();
        long enabled = policies.stream().filter(p -> isGuardrail(p) && Boolean.TRUE.equals(p.getEnabled())).count();
        String status = enabled >= 2 ? GOOD : WARN;
        String detail = "baseline deny-unverified guardrails: " + present + "/2 present, " + enabled + "/2 enabled";
        String why = enabled >= 2 ? "Defense-in-depth lineage guardrails are on."
                : "Baseline deny-unverified forbids are off — defense-in-depth only (an enabled permit would still "
                + "have to allow the request). Consider enabling.";
        return check("guardrail_forbids", "Guardrail forbids on", status, "config", detail, why, 15);
    }

    private static boolean isGuardrail(GatewayPolicyEntity p) {
        String id = p.getCedarPolicyId();
        return "deny-unverified-root".equals(id) || "deny-unverified-actor".equals(id);
    }

    /** Informational: observed evidence the default-deny gate is denying (a clean tenant is NOT penalised). */
    private PostureCheck checkEnforcement(String tenant) {
        long total = 0, denies = 0, defaultDeny = 0, forbidDeny = 0;
        for (Object[] r : pdpRepo.decisionOutcomeCounts(tenant)) {
            String decision = (String) r[0];
            String basis = r[1] == null ? "" : (String) r[1];
            long c = ((Number) r[2]).longValue();
            total += c;
            if ("DENY".equalsIgnoreCase(decision)) {
                denies += c;
                if ("DEFAULT_DENY".equals(basis)) defaultDeny += c;
                else if ("POLICY_MATCH".equals(basis)) forbidDeny += c;
            }
        }
        String detail = denies + " denial(s) of " + total + " decisions (" + defaultDeny + " default-deny, "
                + forbidDeny + " forbid)";
        String why = total == 0 ? "No decisions recorded yet."
                : denies > 0 ? "The default-deny gate is actively denying requests."
                : "No denials observed — expected when all traffic is legitimate (not a weakness under default-deny).";
        return info("enforcement_observed", "Enforcement observed", "observed", detail, why);
    }

    /** Informational: kill-switch awareness — active token/session revocations and blocked users. */
    private PostureCheck checkRevocations(String tenant) {
        LocalDateTime now = LocalDateTime.now();
        int tokenRevs = revRepo.findByWsTenantNameAndExpiresAtAfter(tenant, now).size();
        int sessRevs = sessRevRepo.findByWsTenantNameAndExpiresAtAfter(tenant, now).size();
        int blocked = humanRepo.findByStatusAndWsTenantName("BLOCKED", tenant).size();
        String detail = tokenRevs + " active token revocation(s), " + sessRevs + " session revocation(s), "
                + blocked + " blocked user(s)";
        return info("revocations_blocked", "Revocations / blocked", "config", detail, "Kill-switch state, for awareness.");
    }

    // ── Scoring + notes ────────────────────────────────────────────────────────────

    private static PostureCheck check(String id, String title, String status, String reads,
                                      String detail, String why, int weight) {
        int pts = GOOD.equals(status) ? weight : WARN.equals(status) ? (weight + 1) / 2 : 0;   // WARN = round-half-up
        return new PostureCheck(id, title, status, reads, detail, why, weight, pts);
    }

    /** An informational signal: shown with its real number, but weight 0 — it never pads or dents the score. */
    private static PostureCheck info(String id, String title, String reads, String detail, String why) {
        return new PostureCheck(id, title, INFO, reads, detail, why, 0, 0);
    }

    private static String grade(int score) {
        return score >= 90 ? "A" : score >= 80 ? "B" : score >= 70 ? "C" : score >= 60 ? "D" : "F";
    }

    private List<String> notes(String tenant, AccountabilityReport acct) {
        List<String> notes = new ArrayList<>();
        int pending = agentRepo.findByApprovalStatusAndWsTenantName("PENDING", tenant).size();
        long unregisteredActing = acct.agents().stream().filter(a -> a.approvalStatus() == null).count();
        notes.add(pending + " agent(s) pending approval; " + unregisteredActing + " acting agent name(s) not in the "
                + "registry (each is still default-denied unless a permit grants it).");
        notes.add("Scored for a default-deny gateway: an agent with no permit cannot act, so a tight/scoped policy "
                + "set scores high; the risk signal is broad ENABLED permits, not disabled ones. Enforcement and "
                + "revocations are shown as informational signals (not scored).");
        return notes;
    }
}
