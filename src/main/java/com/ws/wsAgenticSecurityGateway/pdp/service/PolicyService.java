package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyDto;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayPolicyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Policy lifecycle service — CRUD, validation, reload, and audit logging.
 *
 * <p>On startup, loads all enabled policies into the Cedar engine.
 * Policies are created exclusively via the LLM-powered chatbot or REST API —
 * no hardcoded templates exist.
 *
 * <p>Every create/update/delete triggers an automatic policy reload
 * so the Cedar engine always evaluates against the latest policy set.
 */
@Service
@Slf4j
public class PolicyService {

    private final GatewayPolicyRepository repository;
    private final CedarPolicyEngine cedarEngine;
    private final McpAuditService auditService;

    public PolicyService(GatewayPolicyRepository repository,
                         CedarPolicyEngine cedarEngine,
                         McpAuditService auditService) {
        this.repository = repository;
        this.cedarEngine = cedarEngine;
        this.auditService = auditService;
    }

    // ════════════════════════════════════════════════════════════════════
    //  STARTUP — Load policies into Cedar engine
    // ════════════════════════════════════════════════════════════════════

    /**
     * On application startup, load all enabled policies into the Cedar engine.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        long count = repository.count();
        if (count == 0) {
            log.info("🏛️  No policies in DB — use the LLM chatbot or REST API to create policies");
        }
        reloadEngine();
    }

    // ════════════════════════════════════════════════════════════════════
    //  CRUD
    // ════════════════════════════════════════════════════════════════════

    public List<GatewayPolicyEntity> getAllPolicies() {
        return repository.findAll();
    }

    public Optional<GatewayPolicyEntity> getById(UUID id) {
        return repository.findById(id);
    }

    public Optional<GatewayPolicyEntity> getByName(String name) {
        return repository.findByPolicyName(name);
    }

    /**
     * Create a new policy. Validates Cedar syntax before persisting.
     *
     * @return the saved entity, or empty if validation failed
     */
    @Transactional
    public PolicyCreationResult createPolicy(PolicyDto dto) {
        // Validate Cedar syntax
        String validationError = cedarEngine.validatePolicy(dto.getPolicyText());
        if (validationError != null) {
            return PolicyCreationResult.error("Invalid Cedar policy syntax: " + validationError);
        }

        // Check for duplicate name
        if (repository.findByPolicyName(dto.getPolicyName()).isPresent()) {
            return PolicyCreationResult.error("Policy with name '" + dto.getPolicyName() + "' already exists");
        }

        // Generate cedar policy ID if not provided
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
                .build();

        GatewayPolicyEntity saved = repository.save(entity);
        reloadEngine();

        log.info("🏛️  Policy created: {} ({})", saved.getPolicyName(), saved.getEffect());
        Map<String, String> refs = cedarEngine.extractPolicyReferences(saved.getPolicyText());
        auditService.auditPdpPolicyCreated(saved.getPolicyName(), saved.getEffect(),
                saved.getSource() != null ? saved.getSource() : "MANUAL",
                saved.getDescription(), saved.getPolicyText(), saved.getCreatedBy(),
                saved.getTags(), saved.getOriginalPrompt(), refs);
        return PolicyCreationResult.success(saved);
    }

    /**
     * Update an existing policy.
     */
    @Transactional
    public PolicyCreationResult updatePolicy(UUID id, PolicyDto dto) {
        Optional<GatewayPolicyEntity> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return PolicyCreationResult.error("Policy not found: " + id);
        }

        // Validate Cedar syntax if policy text changed
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

        log.info("🏛️  Policy updated: {}", saved.getPolicyName());
        Map<String, String> refs = cedarEngine.extractPolicyReferences(saved.getPolicyText());
        auditService.auditPdpPolicyUpdated(saved.getPolicyName(), "updated via REST API",
                saved.getDescription(), saved.getPolicyText(), saved.getEffect(),
                saved.getTags(), refs);
        return PolicyCreationResult.success(saved);
    }

    /**
     * Delete a policy by ID.
     */
    @Transactional
    public boolean deletePolicy(UUID id) {
        Optional<GatewayPolicyEntity> existing = repository.findById(id);
        if (existing.isPresent()) {
            String policyName = existing.get().getPolicyName();
            repository.deleteById(id);
            reloadEngine();
            log.info("🏛️  Policy deleted: {} ({})", policyName, id);
            auditService.auditPdpPolicyDeleted(policyName);
            return true;
        }
        return false;
    }

    /**
     * Toggle a policy's enabled state.
     */
    @Transactional
    public Optional<GatewayPolicyEntity> toggleEnabled(UUID id) {
        return repository.findById(id).map(entity -> {
            entity.setEnabled(!entity.getEnabled());
            GatewayPolicyEntity saved = repository.save(entity);
            reloadEngine();
            log.info("🏛️  Policy {} → {}", saved.getPolicyName(), saved.getEnabled() ? "ENABLED" : "DISABLED");
            auditService.auditPdpPolicyToggled(saved.getPolicyName(), saved.getEnabled());
            return saved;
        });
    }

    /**
     * Force reload all policies into the Cedar engine.
     */
    public int reloadEngine() {
        List<GatewayPolicyEntity> enabledPolicies = repository.findByEnabledTrueOrderByPriorityAsc();
        int count = cedarEngine.reloadPolicies(enabledPolicies);
        auditService.auditPdpEngineReloaded(Math.max(count, 0));
        return count;
    }

    /**
     * Get policy statistics for the dashboard.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPolicies", repository.count());
        stats.put("enabledPolicies", repository.countByEnabledTrue());
        stats.put("permitPolicies", repository.countByEffect("PERMIT"));
        stats.put("forbidPolicies", repository.countByEffect("FORBID"));
        stats.put("engineLoaded", cedarEngine.hasPolicies());
        return stats;
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Detect effect (PERMIT or FORBID) from Cedar policy text.
     */
    private String detectEffect(String policyText) {
        if (policyText != null) {
            String trimmed = policyText.trim().toLowerCase();
            if (trimmed.contains("forbid")) return "FORBID";
            if (trimmed.contains("permit")) return "PERMIT";
        }
        return "PERMIT";
    }

    /**
     * Result wrapper for policy creation/update operations.
     */
    public record PolicyCreationResult(boolean success, GatewayPolicyEntity policy, String error) {
        public static PolicyCreationResult success(GatewayPolicyEntity policy) {
            return new PolicyCreationResult(true, policy, null);
        }
        public static PolicyCreationResult error(String message) {
            return new PolicyCreationResult(false, null, message);
        }
    }
}
