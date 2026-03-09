package com.ws.wsAgenticSecurityGateway.pdp.service;

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
 * Policy lifecycle service — CRUD, validation, reload, and demo template seeding.
 *
 * <p>On startup, loads all enabled policies into the Cedar engine.
 * If no policies exist, seeds demo templates for demonstration purposes.
 *
 * <p>Every create/update/delete triggers an automatic policy reload
 * so the Cedar engine always evaluates against the latest policy set.
 */
@Service
@Slf4j
public class PolicyService {

    private final GatewayPolicyRepository repository;
    private final CedarPolicyEngine cedarEngine;

    public PolicyService(GatewayPolicyRepository repository,
                         CedarPolicyEngine cedarEngine) {
        this.repository = repository;
        this.cedarEngine = cedarEngine;
    }

    // ════════════════════════════════════════════════════════════════════
    //  STARTUP — Load policies into Cedar engine
    // ════════════════════════════════════════════════════════════════════

    /**
     * On application startup, seed demo policies if none exist,
     * then load all enabled policies into the Cedar engine.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        long count = repository.count();
        if (count == 0) {
            log.info("🏛️  No policies found in DB — seeding demo templates...");
            seedDemoTemplates();
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
        return PolicyCreationResult.success(saved);
    }

    /**
     * Delete a policy by ID.
     */
    @Transactional
    public boolean deletePolicy(UUID id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
            reloadEngine();
            log.info("🏛️  Policy deleted: {}", id);
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
            return saved;
        });
    }

    /**
     * Force reload all policies into the Cedar engine.
     */
    public int reloadEngine() {
        List<GatewayPolicyEntity> enabledPolicies = repository.findByEnabledTrueOrderByPriorityAsc();
        return cedarEngine.reloadPolicies(enabledPolicies);
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
    //  DEMO TEMPLATES
    // ════════════════════════════════════════════════════════════════════

    /**
     * Seed demo policy templates into the database.
     * These demonstrate the Cedar policy capabilities and provide
     * a starting point for the admin.
     *
     * <p>Designed so that swapping to LLM-generated policies is seamless —
     * the only difference is the {@code source} field changes from
     * "TEMPLATE" to "LLM_GENERATED".
     */
    private void seedDemoTemplates() {
        List<GatewayPolicyEntity> templates = List.of(

                // ── Template 1: Allow approved agents to call tools ────────
                GatewayPolicyEntity.builder()
                        .policyName("Approved Agents - Tool Access")
                        .description("Only agents with APPROVED status can invoke tools on any server.")
                        .cedarPolicyId("approved-agents-tool-access")
                        .policyText("""
                                @id("approved-agents-tool-access")
                                permit(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource is Tool
                                )
                                when {
                                    principal in AgentGroup::"APPROVED"
                                };""")
                        .effect("PERMIT")
                        .enabled(true)
                        .priority(10)
                        .tags("security,agent-access")
                        .source("TEMPLATE")
                        .createdBy("system")
                        .build(),

                // ── Template 2: Block all tools on a specific server ──────
                GatewayPolicyEntity.builder()
                        .policyName("Block Production Server Access")
                        .description("Forbid all tool calls to the 'production-db' server regardless of agent.")
                        .cedarPolicyId("block-production-server")
                        .policyText("""
                                @id("block-production-server")
                                forbid(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource is Tool
                                )
                                when {
                                    resource in Server::"production-db"
                                };""")
                        .effect("FORBID")
                        .enabled(false)
                        .priority(5)
                        .tags("security,server-restriction")
                        .source("TEMPLATE")
                        .createdBy("system")
                        .build(),

                // ── Template 3: Business hours only ───────────────────────
                GatewayPolicyEntity.builder()
                        .policyName("Business Hours Restriction")
                        .description("Deny tool calls outside business hours (Mon-Fri, 8am-6pm).")
                        .cedarPolicyId("business-hours-only")
                        .policyText("""
                                @id("business-hours-only")
                                forbid(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource is Tool
                                )
                                unless {
                                    context.businessHours == true
                                };""")
                        .effect("FORBID")
                        .enabled(false)
                        .priority(20)
                        .tags("time-based,compliance")
                        .source("TEMPLATE")
                        .createdBy("system")
                        .build(),

                // ── Template 4: Allow all prompts and resource reads ──────
                GatewayPolicyEntity.builder()
                        .policyName("Allow Discovery Operations")
                        .description("Allow all agents to get prompts and read resources (read-only discovery).")
                        .cedarPolicyId("allow-discovery-ops")
                        .policyText("""
                                @id("allow-discovery-ops")
                                permit(
                                    principal is Agent,
                                    action in [Action::"promptGet", Action::"resourceRead"],
                                    resource
                                );""")
                        .effect("PERMIT")
                        .enabled(true)
                        .priority(15)
                        .tags("discovery,read-only")
                        .source("TEMPLATE")
                        .createdBy("system")
                        .build(),

                // ── Template 5: Block specific agent from everything ──────
                GatewayPolicyEntity.builder()
                        .policyName("Block Rogue Agent")
                        .description("Forbid a specific agent from performing any operation (entity-level targeting).")
                        .cedarPolicyId("block-rogue-agent")
                        .policyText("""
                                @id("block-rogue-agent")
                                forbid(
                                    principal == Agent::"rogue-agent",
                                    action,
                                    resource
                                );""")
                        .effect("FORBID")
                        .enabled(false)
                        .priority(1)
                        .tags("security,agent-block,granular")
                        .source("TEMPLATE")
                        .createdBy("system")
                        .build(),

                // ── Template 6: Approved agents + specific server + business hours ──
                GatewayPolicyEntity.builder()
                        .policyName("Approved Agents - GitHub During Business Hours")
                        .description("Allow APPROVED agents to use GitHub tools only during business hours (combined conditions with &&).")
                        .cedarPolicyId("approved-github-business-hours")
                        .policyText("""
                                @id("approved-github-business-hours")
                                permit(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource is Tool
                                )
                                when {
                                    principal in AgentGroup::"APPROVED" &&
                                    resource in Server::"github" &&
                                    context.businessHours == true
                                };""")
                        .effect("PERMIT")
                        .enabled(false)
                        .priority(12)
                        .tags("granular,combined-conditions,time-based")
                        .source("TEMPLATE")
                        .createdBy("system")
                        .build(),

                // ── Template 7: Block dangerous argument patterns ─────────
                GatewayPolicyEntity.builder()
                        .policyName("Block Production Data in Arguments")
                        .description("Forbid any tool call whose arguments contain 'production' (argument pattern matching).")
                        .cedarPolicyId("block-production-args")
                        .policyText("""
                                @id("block-production-args")
                                forbid(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource is Tool
                                )
                                when {
                                    context.argumentsFlat like "*production*"
                                };""")
                        .effect("FORBID")
                        .enabled(false)
                        .priority(3)
                        .tags("security,argument-inspection,granular")
                        .source("TEMPLATE")
                        .createdBy("system")
                        .build(),

                // ── Template 8: Allow specific agent to call specific tool ─
                GatewayPolicyEntity.builder()
                        .policyName("Allow Claude Desktop - GitHub Create Issue")
                        .description("Allow only 'claude-desktop' agent to call the 'github_create_issue' tool (precise entity-level control).")
                        .cedarPolicyId("allow-claude-github-create-issue")
                        .policyText("""
                                @id("allow-claude-github-create-issue")
                                permit(
                                    principal == Agent::"claude-desktop",
                                    action == Action::"toolCall",
                                    resource == Tool::"github_create_issue"
                                );""")
                        .effect("PERMIT")
                        .enabled(false)
                        .priority(8)
                        .tags("granular,agent-specific,tool-specific")
                        .source("TEMPLATE")
                        .createdBy("system")
                        .build()
        );

        for (GatewayPolicyEntity template : templates) {
            try {
                repository.save(template);
                log.info("🏛️  Seeded demo policy: {} ({})", template.getPolicyName(), template.getEffect());
            } catch (Exception e) {
                log.warn("🏛️  Could not seed policy '{}': {}", template.getPolicyName(), e.getMessage());
            }
        }
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
