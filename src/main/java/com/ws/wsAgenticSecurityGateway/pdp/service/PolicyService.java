package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyDto;
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
            repository.save(GatewayPolicyEntity.builder()
                    .policyName(dp.name())
                    .description(dp.description())
                    .cedarPolicyId(dp.name())
                    .policyText(dp.text())
                    .effect("FORBID")
                    .enabled(true)
                    .priority(10) // low number = evaluated early; guardrails before allow policies
                    .source("DEFAULT")
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

    public Optional<GatewayPolicyEntity> getById(UUID id) {
        return repository.findByIdAndWsTenantName(id, TenantContext.get());
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

        String cedarId = dto.getCedarPolicyId();
        if (cedarId == null || cedarId.isBlank()) {
            cedarId = dto.getPolicyName().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-|-$", "");
        }

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
            entity.setPolicyText(dto.getPolicyText());
            entity.setEffect(dto.getEffect() != null ? dto.getEffect().toUpperCase() : detectEffect(dto.getPolicyText()));
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
        return repository.findByIdAndWsTenantName(id, TenantContext.get()).map(entity -> {
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
