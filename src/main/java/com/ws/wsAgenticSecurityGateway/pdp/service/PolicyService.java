package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyDto;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationResult;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayPolicyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Slf4j
public class PolicyService {

    /**
     * Stage-2 baseline lineage guardrails (LD-3): deny any call whose delegation root or acting agent is
     * unverified. Expressed only in {@code act_chain} attributes the gateway already populates before PDP,
     * so they gate with no new plumbing. Seeded per tenant with {@code source=DEFAULT}; a matching forbid
     * short-circuits over any permit, so an unverified lineage is denied even under a broad allow policy.
     */
    private static final List<DefaultPolicy> DEFAULT_LINEAGE_POLICIES = List.of(
            new DefaultPolicy("deny-unverified-root",
                    "forbid(principal, action, resource) when { context.rootVerified == false };",
                    "Baseline lineage guardrail: deny any call whose delegation root (the human/NHI the agent "
                            + "acts on behalf of) is not a verified identity."),
            new DefaultPolicy("deny-unverified-actor",
                    "forbid(principal, action, resource) when { context.actorVerified == false };",
                    "Baseline lineage guardrail: deny any call whose acting agent is not a verified, "
                            + "registered identity."));

    private final GatewayPolicyRepository repository;
    private final CedarPolicyEngine cedarEngine;
    private final GatewayAuditService auditService;
    private final boolean seedDefaultLineage;

    public PolicyService(GatewayPolicyRepository repository,
                         CedarPolicyEngine cedarEngine,
                         GatewayAuditService auditService,
                         @Value("${ws.gateway.policy.seed-default-lineage:true}") boolean seedDefaultLineage) {
        this.repository = repository;
        this.cedarEngine = cedarEngine;
        this.auditService = auditService;
        this.seedDefaultLineage = seedDefaultLineage;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        // Wire the engine's lazy-load hook so a tenant first seen at request time gets its slot populated
        // (and its baseline guardrails seeded) without a restart.
        cedarEngine.setTenantLoader(this::loadEnabledForTenant);

        if (seedDefaultLineage) {
            List<String> tenants = repository.findDistinctWsTenantName();
            int totalSeeded = 0;
            for (String tenant : tenants) {
                totalSeeded += seedDefaultLineagePolicies(tenant);
            }
            if (totalSeeded > 0) {
 log.info("Seeded {} default lineage policy(ies) across {} tenant(s)", totalSeeded, tenants.size());
            }
        }
        long count = repository.count();
        if (count == 0) {
 log.info("No policies in DB — use the LLM chatbot or REST API to create policies");
        }
        backfillPrincipals(); // populate the principal read-model for any pre-existing rows
        reloadEngine();
    }

    /**
     * Engine lazy-load hook (invoked on the first evaluation for an unseen tenant): idempotently seed the
     * tenant's baseline guardrails, then return its enabled policies. Keeps a brand-new tenant from silently
     * running with zero guardrails — and, crucially, from falling through to another tenant's policies.
     */
    private List<GatewayPolicyEntity> loadEnabledForTenant(String tenant) {
        if (seedDefaultLineage) {
            seedDefaultLineagePolicies(tenant);
        }
        return repository.findByEnabledTrueAndWsTenantNameOrderByPriorityAsc(tenant);
    }

    /**
     * Idempotently install the baseline lineage guardrails for one tenant (LD-3). Skips any policy already
     * present for that tenant (unique by {@code policy_name, ws_tenant_name}), so it is safe on every restart.
     * The tenant is stamped explicitly, so it works at startup when {@code TenantContext} is null (the
     * tenant listener does not overwrite an already-set value). Returns the number of policies inserted.
     */
    @Transactional
    public int seedDefaultLineagePolicies(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return 0;
        }
        int seeded = 0;
        for (DefaultPolicy dp : DEFAULT_LINEAGE_POLICIES) {
            if (repository.findByPolicyNameAndWsTenantName(dp.name(), tenant).isPresent()) {
                continue; // already present for this tenant — idempotent
            }
            CedarPolicyEngine.PolicyPrincipal principal = cedarEngine.extractPrincipal(dp.text());
            repository.save(GatewayPolicyEntity.builder()
                    .policyName(dp.name())
                    .description(dp.description())
                    .cedarPolicyId(dp.name())
                    .policyText(dp.text())
                    .effect("FORBID")
                    .enabled(true)
                    .priority(10) // low number = evaluated early; guardrails before allow policies
                    .source("DEFAULT")
                    .principalKind(principal.kind()) // ANY — these guardrails apply to every agent
                    .principalId(principal.id())
                    .createdBy("system")
                    .wsTenantName(tenant)
                    .build());
            seeded++;
 log.info("Seeded default lineage policy '{}' for tenant '{}'", dp.name(), tenant);
        }
        return seeded;
    }

    private record DefaultPolicy(String name, String text, String description) {}

    public List<GatewayPolicyEntity> getAllPolicies() {
        return repository.findAllByWsTenantName(TenantContext.get());
    }

    /**
     * The policies that govern one agent, for the admin "what policies does agent X have?" view. Answered from
     * the indexed principal read-model, not a Cedar-text scan. Union of:
     * <ul>
     *   <li>policies that name the agent directly — {@code principal == Agent::"X"} (kind AGENT); and</li>
     *   <li>tenant-wide policies that apply to <em>every</em> agent — the baseline guardrails / wildcards
     *       (kind ANY) and type-scoped policies (kind AGENT_TYPE, e.g. {@code principal is Agent}).</li>
     * </ul>
     * Group-scoped policies (kind AGENT_GROUP) are intentionally excluded: an agent's group membership rides on
     * the delegated token at request time and is not stored on the agent, so it cannot be resolved here without
     * risking false attribution. Deduped by id, ordered by priority then name — the same order the PDP evaluates.
     * (This is a read-model for admin visibility only; enforcement still runs over {@code policy_text}.)
     */
    public List<GatewayPolicyEntity> getPoliciesForAgent(String agentName) {
        String tenant = TenantContext.get();
        if (agentName == null || agentName.isBlank()) {
            return getAllPolicies();
        }
        Map<UUID, GatewayPolicyEntity> merged = new LinkedHashMap<>();
        for (GatewayPolicyEntity p : repository
                .findByWsTenantNameAndPrincipalKindAndPrincipalId(tenant, "AGENT", agentName)) {
            merged.put(p.getId(), p);
        }
        for (GatewayPolicyEntity p : repository
                .findByWsTenantNameAndPrincipalKindIn(tenant, List.of("ANY", "AGENT_TYPE"))) {
            merged.putIfAbsent(p.getId(), p);
        }
        return merged.values().stream()
                .sorted(Comparator
                        .comparingInt((GatewayPolicyEntity p) -> p.getPriority() == null ? 100 : p.getPriority())
                        .thenComparing(p -> p.getPolicyName() == null ? "" : p.getPolicyName()))
                .toList();
    }

    /**
     * One-time backfill of the principal read-model for rows saved before the columns existed
     * ({@code principalKind == null}). Idempotent — only null rows are touched, recomputed from the same
     * {@code policy_text} the PDP enforces. New create/update/seed paths populate the columns inline, so after
     * one pass this becomes a no-op. Runs at startup alongside guardrail seeding.
     */
    @Transactional
    public int backfillPrincipals() {
        List<GatewayPolicyEntity> stale = repository.findByPrincipalKindIsNull();
        for (GatewayPolicyEntity p : stale) {
            CedarPolicyEngine.PolicyPrincipal principal = cedarEngine.extractPrincipal(p.getPolicyText());
            p.setPrincipalKind(principal.kind());
            p.setPrincipalId(principal.id());
            repository.save(p);
        }
        if (!stale.isEmpty()) {
 log.info("Backfilled principal read-model for {} policy(ies)", stale.size());
        }
        return stale.size();
    }

    public Optional<GatewayPolicyEntity> getById(UUID id) {
        return repository.findByIdAndWsTenantName(id, TenantContext.get());
    }

    /**
     * Author-time dry-run: evaluate {@code req} against the current tenant's enabled policies, optionally plus
     * an unsaved {@code draftPolicyText}, without persisting anything. Powers the "Test a policy" panel.
     */
    public PolicyEvaluationResult testDecision(PolicyEvaluationRequest req, String draftPolicyText) {
        String tenant = TenantContext.get();
        List<GatewayPolicyEntity> policies = new ArrayList<>(
                repository.findByEnabledTrueAndWsTenantNameOrderByPriorityAsc(tenant));
        if (draftPolicyText != null && !draftPolicyText.isBlank()) {
            // Insert the unsaved draft at the front (lowest priority number = evaluated first) so the author
            // sees its effect layered over the saved set.
            policies.add(0, GatewayPolicyEntity.builder()
                    .policyName("__draft__")
                    .policyText(draftPolicyText)
                    .effect(detectEffect(draftPolicyText))
                    .enabled(true)
                    .priority(0)
                    .wsTenantName(tenant)
                    .build());
        }
        return cedarEngine.evaluateEntities(policies, req);
    }

    /** True when a policy is a system-seeded guardrail (read-only — must not be edited/deleted by tenants). */
    private static boolean isProtected(GatewayPolicyEntity p) {
        return p != null && "DEFAULT".equalsIgnoreCase(p.getSource());
    }

    private static final java.util.regex.Pattern CEDAR_ID = java.util.regex.Pattern.compile("@id\\(\"([^\"]+)\"\\)");

    /** The Cedar {@code @id} annotation in the policy text — the policy's canonical identity — or null. */
    private static String extractCedarId(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = CEDAR_ID.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Existing policy (this tenant) that already uses the given Cedar {@code @id}, if any — for duplicate detection. */
    public Optional<GatewayPolicyEntity> findByCedarId(String cedarId) {
        if (cedarId == null || cedarId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByCedarPolicyIdAndWsTenantName(cedarId, TenantContext.get());
    }

    /**
     * Baseline decision for {@code req} against the tenant's saved policies EXCLUDING the policy being authored
     * (matched by {@code excludeCedarId}) and WITHOUT the draft — so a caller can compare "with vs without" the
     * draft to detect that it overrides, or is redundant with, an existing policy.
     */
    public PolicyEvaluationResult testBaseline(PolicyEvaluationRequest req, String excludeCedarId) {
        String tenant = TenantContext.get();
        List<GatewayPolicyEntity> policies = repository
                .findByEnabledTrueAndWsTenantNameOrderByPriorityAsc(tenant).stream()
                .filter(p -> excludeCedarId == null || !excludeCedarId.equals(p.getCedarPolicyId()))
                .toList();
        return cedarEngine.evaluateEntities(policies, req);
    }

    @Transactional
    public PolicyCreationResult createPolicy(PolicyDto dto) {
        String validationError = cedarEngine.validatePolicy(dto.getPolicyText());
        if (validationError != null) {
            return PolicyCreationResult.error("Invalid Cedar policy syntax: " + validationError);
        }

        String tenant = TenantContext.get();
        if (repository.findByPolicyNameAndWsTenantName(dto.getPolicyName(), tenant).isPresent()) {
            return PolicyCreationResult.error("Policy with name '" + dto.getPolicyName() + "' already exists");
        }

        // The Cedar @id is the policy's canonical identity — take it from the text, and enforce it unique so
        // audit ("which policy decided this") is never ambiguous. Fall back to a name slug when there's no @id.
        String cedarId = extractCedarId(dto.getPolicyText());
        if (cedarId == null || cedarId.isBlank()) {
            cedarId = dto.getCedarPolicyId();
        }
        if (cedarId == null || cedarId.isBlank()) {
            cedarId = dto.getPolicyName().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-|-$", "");
        }
        Optional<GatewayPolicyEntity> idClash = repository.findByCedarPolicyIdAndWsTenantName(cedarId, tenant);
        if (idClash.isPresent()) {
            return PolicyCreationResult.error("Policy id '" + cedarId + "' is already used by policy '"
                    + idClash.get().getPolicyName() + "'. Give this policy a unique @id.");
        }

        // Derive the queryable principal read-model from the Cedar text (recomputed on every save, never authored).
        CedarPolicyEngine.PolicyPrincipal principal = cedarEngine.extractPrincipal(dto.getPolicyText());

        GatewayPolicyEntity entity = GatewayPolicyEntity.builder()
                .policyName(dto.getPolicyName())
                .description(dto.getDescription())
                .cedarPolicyId(cedarId)
                .policyText(dto.getPolicyText())
                .effect(dto.getEffect() != null ? dto.getEffect().toUpperCase() : detectEffect(dto.getPolicyText()))
                .enabled(dto.getEnabled() != null ? dto.getEnabled() : true)
                .priority(dto.getPriority() != null ? dto.getPriority() : 100)
                .tags(dto.getTags())
                .source(dto.getSource() != null ? dto.getSource() : "MANUAL")
                .originalPrompt(dto.getOriginalPrompt())
                .principalKind(principal.kind())
                .principalId(principal.id())
                .createdBy(dto.getCreatedBy() != null ? dto.getCreatedBy() : "admin")
                .wsTenantName(tenant)
                .build();

        GatewayPolicyEntity saved = repository.save(entity);
        reloadEngine();

 log.info("Policy created: {} ({})", saved.getPolicyName(), saved.getEffect());
        Map<String, String> refs = cedarEngine.extractPolicyReferences(saved.getPolicyText());
        auditService.auditPdpPolicyCreated(saved.getPolicyName(), saved.getEffect(),
                saved.getSource() != null ? saved.getSource() : "MANUAL",
                saved.getDescription(), saved.getPolicyText(), saved.getCreatedBy(),
                saved.getTags(), saved.getOriginalPrompt(), refs);
        return PolicyCreationResult.success(saved);
    }

    @Transactional
    public PolicyCreationResult updatePolicy(UUID id, PolicyDto dto) {
        Optional<GatewayPolicyEntity> existing = repository.findByIdAndWsTenantName(id, TenantContext.get());
        if (existing.isEmpty()) {
            return PolicyCreationResult.error("Policy not found: " + id);
        }
        if (isProtected(existing.get())) {
            return PolicyCreationResult.error("This is a system guardrail policy (source=DEFAULT) and is read-only.");
        }

        if (dto.getPolicyText() != null) {
            String validationError = cedarEngine.validatePolicy(dto.getPolicyText());
            if (validationError != null) {
                return PolicyCreationResult.error("Invalid Cedar policy syntax: " + validationError);
            }
        }

        GatewayPolicyEntity entity = existing.get();
        if (dto.getPolicyName() != null) entity.setPolicyName(dto.getPolicyName());
        if (dto.getDescription() != null) entity.setDescription(dto.getDescription());
        if (dto.getPolicyText() != null) {
            // Keep the canonical id (@id) unique — reject if the edited text collides with a DIFFERENT policy.
            String newCedarId = extractCedarId(dto.getPolicyText());
            if (newCedarId != null && !newCedarId.isBlank()) {
                Optional<GatewayPolicyEntity> clash =
                        repository.findByCedarPolicyIdAndWsTenantName(newCedarId, TenantContext.get());
                if (clash.isPresent() && !clash.get().getId().equals(id)) {
                    return PolicyCreationResult.error("Policy id '" + newCedarId + "' is already used by policy '"
                            + clash.get().getPolicyName() + "'. Give this policy a unique @id.");
                }
                entity.setCedarPolicyId(newCedarId);
            }
            entity.setPolicyText(dto.getPolicyText());
            entity.setEffect(dto.getEffect() != null ? dto.getEffect().toUpperCase() : detectEffect(dto.getPolicyText()));
            // The principal scope can change with the text — recompute the read-model so it never drifts.
            CedarPolicyEngine.PolicyPrincipal principal = cedarEngine.extractPrincipal(dto.getPolicyText());
            entity.setPrincipalKind(principal.kind());
            entity.setPrincipalId(principal.id());
        }
        if (dto.getEnabled() != null) entity.setEnabled(dto.getEnabled());
        if (dto.getPriority() != null) entity.setPriority(dto.getPriority());
        if (dto.getTags() != null) entity.setTags(dto.getTags());

        GatewayPolicyEntity saved = repository.save(entity);
        reloadEngine();

 log.info("Policy updated: {}", saved.getPolicyName());
        Map<String, String> refs = cedarEngine.extractPolicyReferences(saved.getPolicyText());
        auditService.auditPdpPolicyUpdated(saved.getPolicyName(), "updated via REST API",
                saved.getDescription(), saved.getPolicyText(), saved.getEffect(),
                saved.getTags(), refs);
        return PolicyCreationResult.success(saved);
    }

    @Transactional
    public boolean deletePolicy(UUID id) {
        Optional<GatewayPolicyEntity> existing = repository.findByIdAndWsTenantName(id, TenantContext.get());
        if (existing.isPresent()) {
            if (isProtected(existing.get())) {
 log.warn("Refused to delete system guardrail policy '{}' (source=DEFAULT)", existing.get().getPolicyName());
                return false;
            }
            String policyName = existing.get().getPolicyName();
            repository.deleteById(id);
            reloadEngine();
 log.info("Policy deleted: {} ({})", policyName, id);
            auditService.auditPdpPolicyDeleted(policyName);
            return true;
        }
        return false;
    }

    @Transactional
    public Optional<GatewayPolicyEntity> toggleEnabled(UUID id) {
        return repository.findByIdAndWsTenantName(id, TenantContext.get())
                .filter(entity -> {
                    if (isProtected(entity)) {
 log.warn("Refused to toggle system guardrail policy '{}' (source=DEFAULT)", entity.getPolicyName());
                        return false;
                    }
                    return true;
                })
                .map(entity -> {
            entity.setEnabled(!entity.getEnabled());
            GatewayPolicyEntity saved = repository.save(entity);
            reloadEngine();
 log.info("Policy {} → {}", saved.getPolicyName(), saved.getEnabled() ? "ENABLED": "DISABLED");
            auditService.auditPdpPolicyToggled(saved.getPolicyName(), saved.getEnabled());
            return saved;
        });
    }

    /**
     * Reload the engine's compiled policy sets. A tenant-scoped call (admin CRUD) reloads ONLY that tenant's
     * isolated slot and refreshes the fallback set — it never clobbers other tenants' slots. A no-tenant call
     * (startup) loads the fallback set plus every known tenant's slot. This is the fix for the old
     * single-shared-list, last-writer-wins behavior.
     */
    public int reloadEngine() {
        String tenant = TenantContext.get();
        if (tenant != null && !tenant.isBlank()) {
            int count = cedarEngine.reloadTenant(tenant,
                    repository.findByEnabledTrueAndWsTenantNameOrderByPriorityAsc(tenant));
            cedarEngine.reloadGlobal(repository.findByEnabledTrueOrderByPriorityAsc());
            auditService.auditPdpEngineReloaded(Math.max(count, 0));
            return count;
        }
        // Startup / no tenant context: load the fallback set + each tenant's isolated slot.
        int globalCount = cedarEngine.reloadGlobal(repository.findByEnabledTrueOrderByPriorityAsc());
        for (String t : repository.findDistinctWsTenantName()) {
            cedarEngine.reloadTenant(t, repository.findByEnabledTrueAndWsTenantNameOrderByPriorityAsc(t));
        }
        auditService.auditPdpEngineReloaded(Math.max(globalCount, 0));
        return globalCount;
    }

    public Map<String, Object> getStats() {
        String tenant = TenantContext.get();
        Map<String, Object> stats = new LinkedHashMap<>();
        if (tenant != null) {
            stats.put("totalPolicies", repository.findAllByWsTenantName(tenant).size());
            stats.put("enabledPolicies", repository.countByEnabledTrueAndWsTenantName(tenant));
            stats.put("permitPolicies", repository.countByEffectAndWsTenantName("PERMIT", tenant));
            stats.put("forbidPolicies", repository.countByEffectAndWsTenantName("FORBID", tenant));
        } else {
            stats.put("totalPolicies", repository.count());
            stats.put("enabledPolicies", repository.countByEnabledTrue());
            stats.put("permitPolicies", repository.countByEffect("PERMIT"));
            stats.put("forbidPolicies", repository.countByEffect("FORBID"));
        }
        stats.put("engineLoaded", cedarEngine.hasPolicies(tenant));
        return stats;
    }

    private String detectEffect(String policyText) {
        if (policyText != null) {
            String trimmed = policyText.trim().toLowerCase();
            if (trimmed.contains("forbid")) return "FORBID";
            if (trimmed.contains("permit")) return "PERMIT";
        }
        return "PERMIT";
    }

    public record PolicyCreationResult(boolean success, GatewayPolicyEntity policy, String error) {
        public static PolicyCreationResult success(GatewayPolicyEntity policy) {
            return new PolicyCreationResult(true, policy, null);
        }
        public static PolicyCreationResult error(String message) {
            return new PolicyCreationResult(false, null, message);
        }
    }
}
