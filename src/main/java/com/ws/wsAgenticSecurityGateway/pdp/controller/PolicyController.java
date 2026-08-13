package com.ws.wsAgenticSecurityGateway.pdp.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyChatRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyChatResponse;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyDto;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationResult;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyActivityReport;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyLlmService;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyService;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyService.PolicyCreationResult;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyActivityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/policies")
@Slf4j
public class PolicyController {

    private final PolicyService policyService;
    private final PolicyLlmService llmService;
    private final CedarPolicyEngine cedarEngine;
    private final GatewayAuditService auditService;
    private final AgentRegistryService agentRegistryService;
    private final CapabilityRegistryService capabilityRegistry;
    private final PolicyActivityService policyActivityService;

    public PolicyController(PolicyService policyService,
                            PolicyLlmService llmService,
                            CedarPolicyEngine cedarEngine,
                            GatewayAuditService auditService,
                            AgentRegistryService agentRegistryService,
                            CapabilityRegistryService capabilityRegistry,
                            PolicyActivityService policyActivityService) {
        this.policyService = policyService;
        this.llmService = llmService;
        this.cedarEngine = cedarEngine;
        this.auditService = auditService;
        this.agentRegistryService = agentRegistryService;
        this.capabilityRegistry = capabilityRegistry;
        this.policyActivityService = policyActivityService;
    }

    /**
     * The CISO Policy-Activity report — per-policy allow/deny/last-fired, dead policies, and decision coverage,
     * all from the {@code pdp_audit_log} decision ledger. Read-only visibility; scoped to the caller's tenant.
     */
    @GetMapping("/activity")
    public ResponseEntity<PolicyActivityReport> policyActivity() {
        return ResponseEntity.ok(policyActivityService.getPolicyActivity());
    }

    /**
     * List policies for the caller's tenant. With {@code ?agentId=X} it returns only the policies that govern
     * that agent — the ones naming it directly plus the tenant-wide guardrails/wildcards that apply to every
     * agent — answered from the indexed principal read-model rather than a Cedar-text scan.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listPolicies(
            @RequestParam(required = false) String agentId) {
        List<GatewayPolicyEntity> policies = (agentId != null && !agentId.isBlank())
                ? policyService.getPoliciesForAgent(agentId)
                : policyService.getAllPolicies();
        List<Map<String, Object>> result = policies.stream()
                .map(this::toMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getPolicy(@PathVariable UUID id) {
        return policyService.getById(id)
                .map(p -> ResponseEntity.ok(toMap(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createPolicy(@RequestBody PolicyDto dto) {
        PolicyCreationResult result = policyService.createPolicy(dto);
        if (result.success()) {
            return ResponseEntity.ok(toMap(result.policy()));
        }
        return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updatePolicy(@PathVariable UUID id,
                                                              @RequestBody PolicyDto dto) {
        PolicyCreationResult result = policyService.updatePolicy(id, dto);
        if (result.success()) {
            return ResponseEntity.ok(toMap(result.policy()));
        }
        return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deletePolicy(@PathVariable UUID id) {
        if (policyService.deletePolicy(id)) {
            return ResponseEntity.ok(Map.of("deleted", true, "id", id.toString()));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> togglePolicy(@PathVariable UUID id) {
        return policyService.toggleEnabled(id)
                .map(p -> ResponseEntity.ok(toMap(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadPolicies() {
        int count = policyService.reloadEngine();
        return ResponseEntity.ok(Map.of(
                "reloaded", count >= 0,
                "policyCount", Math.max(count, 0),
                "error", count < 0 ? "Failed to parse policies" : ""
        ));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = policyService.getStats();
        stats.put("llmAvailable", llmService.isLlmAvailable());
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validatePolicy(@RequestBody Map<String, String> body) {
        String policyText = body.get("policyText");
        if (policyText == null || policyText.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", "policyText is required"));
        }
        String error = cedarEngine.validatePolicy(policyText);
        boolean valid = (error == null);
        auditService.auditPdpPolicyValidated(policyText, valid, error);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("valid", valid);
        if (!valid) {
            resp.put("error", error);
        } else {
            // Surface the semantic warning that was previously computed only on the LLM path — e.g. the
            // forbid-with-unless trap (looks like it grants, but under default-deny it grants nothing).
            String warning = cedarEngine.getSemanticWarnings(policyText);
            if (warning != null) {
                resp.put("warning", warning);
            }
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * Author-time "test a policy": dry-run a sample request (built from the admin's REAL agents/tools) against
     * the tenant's saved policies, optionally plus an unsaved {@code draftPolicyText}, and return the decision
     * without persisting anything. Lets an author see ALLOW/DENY + which policy matched before saving.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testPolicy(@RequestBody Map<String, Object> body) {
        String draft = str(body.get("draftPolicyText"));

        // Derive the request the policy is ABOUT straight from its text (agent/action/resource it names), so
        // the author doesn't re-enter what the policy already says. Any explicit body field overrides the
        // derived value (the "customize / what-if" path, useful for generic policies).
        Map<String, String> refs = (draft != null && !draft.isBlank())
                ? cedarEngine.extractPolicyReferences(draft) : Map.of();
        String agentName    = firstNonBlank(str(body.get("agentName")),    refs.get("agentName"));
        String action       = firstNonBlank(str(body.get("action")),       refs.get("actionName"), "toolCall");
        String resourceType = firstNonBlank(str(body.get("resourceType")), refs.get("capabilityType"), "Tool");
        String resourceName = firstNonBlank(str(body.get("resourceName")), refs.get("capabilityName"));
        String serverName   = firstNonBlank(str(body.get("serverName")),   refs.get("serverName"));

        PolicyEvaluationRequest req = PolicyEvaluationRequest.builder()
                .agentName(agentName)
                .agentVersion(str(body.get("agentVersion")))
                .agentApprovalStatus(str(body.get("approvalStatus")))
                .action(action)
                .resourceType(resourceType)
                .resourceName(resourceName)
                .serverName(serverName)
                .agentRoles(strList(body.get("roles")))
                .agentGroups(strList(body.get("groups")))
                .build();

        // Minimal delegation lineage from the verified flags, so the baseline guardrails can be exercised.
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", orDefault(str(body.get("rootType")), "human"));
        root.put("id", orDefault(str(body.get("rootId")), "test-root"));
        root.put("verified", !Boolean.FALSE.equals(body.get("rootVerified")));
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", "agent");
        actor.put("id", orDefault(agentName, "test-agent"));
        actor.put("verified", !Boolean.FALSE.equals(body.get("actorVerified")));
        req.setActChain(List.of(root, actor));

        PolicyEvaluationResult result = policyService.testDecision(req, draft);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("decision", result.getDecision());
        resp.put("matchedPolicies", result.getMatchedPolicies());
        resp.put("reason", result.getReason());
        resp.put("diagnostics", result.getDiagnostics());
        // Echo back exactly what request we evaluated, so the UI can show "tested: agent X → tool Z".
        Map<String, Object> evaluated = new LinkedHashMap<>();
        evaluated.put("agentName", agentName);
        evaluated.put("action", action);
        evaluated.put("resourceType", resourceType);
        evaluated.put("resourceName", resourceName);
        evaluated.put("serverName", serverName);
        resp.put("evaluatedRequest", evaluated);
        return ResponseEntity.ok(resp);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * Full pre-save policy check: (1) syntax + semantic warning, (2) REFERENCE validation against the REAL
     * registries — every agent/tool/server the policy names must actually exist (this is what catches a typo
     * like {@code github_ge_me} that would otherwise be a silently-dead policy), and (3) if all references are
     * real, whether the policy takes effect or is overridden by a forbid. Nothing is persisted.
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> checkPolicy(@RequestBody Map<String, String> body) {
        String text = body.get("policyText");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", "policyText is required"));
        }
        Map<String, Object> resp = new LinkedHashMap<>();

        // 1. Syntax
        String error = cedarEngine.validatePolicy(text);
        boolean valid = (error == null);
        resp.put("valid", valid);
        if (!valid) {
            resp.put("error", error);
            return ResponseEntity.ok(resp);
        }
        String warning = cedarEngine.getSemanticWarnings(text);
        if (warning != null) {
            resp.put("warning", warning);
        }

        // 2. References vs. real registered entities
        Map<String, String> refs = cedarEngine.extractPolicyReferences(text);
        List<Map<String, Object>> references = new ArrayList<>();
        String agent = refs.get("agentName");
        if (agent != null) {
            boolean known = !agentRegistryService.findAgentsByName(agent).isEmpty();
            references.add(refEntry("agent", agent, known));
        }
        String cap = refs.get("capabilityName");
        if (cap != null) {
            String kind = refs.getOrDefault("capabilityType", "Tool").toLowerCase();
            references.add(refEntry(kind, cap, capabilityRegistry.exists(cap)));
        }
        String server = refs.get("serverName");
        if (server != null) {
            references.add(refEntry("server", server, capabilityRegistry.getRegisteredServerNames().contains(server)));
        }
        resp.put("references", references);
        boolean allKnown = references.stream().allMatch(r -> Boolean.TRUE.equals(r.get("known")));
        resp.put("allReferencesKnown", allKnown);

        // Duplicate @id: the Cedar @id is meant to identify a policy — flag if another already uses it.
        String cedarId = extractCedarId(text);
        if (cedarId != null) {
            policyService.findByCedarId(cedarId).ifPresent(existing -> {
                resp.put("idDuplicate", true);
                resp.put("idDuplicateOf", existing.getPolicyName());
            });
        }

        // 3. Effect (only meaningful when the referenced entities are real): derive the policy's own target and
        // see whether it grants/blocks, or is overridden by a forbid.
        resp.put("effect", detectEffectPublic(text));
        PolicyEvaluationRequest req = PolicyEvaluationRequest.builder()
                .agentName(agent)
                .action(firstNonBlank(refs.get("actionName"), "toolCall"))
                .resourceType(firstNonBlank(refs.get("capabilityType"), "Tool"))
                .resourceName(cap)
                .serverName(server)
                .build();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "human"); root.put("id", "check-root"); root.put("verified", true);
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", "agent"); actor.put("id", orDefault(agent, "check-agent")); actor.put("verified", true);
        req.setActChain(List.of(root, actor));
        PolicyEvaluationResult result = policyService.testDecision(req, text);
        resp.put("decision", result.getDecision());
        resp.put("matchedPolicies", result.getMatchedPolicies());

        // 4. Conflict: how the SAME target is decided WITHOUT this draft (excluding a same-@id policy being
        // edited). Comparing with vs without reveals "this forbid overrides an existing permit" or
        // "this permit is redundant — already allowed by X".
        if (allKnown) {
            PolicyEvaluationResult baseline = policyService.testBaseline(req, cedarId);
            resp.put("baselineDecision", baseline.getDecision());
            resp.put("baselineMatched", baseline.getMatchedPolicies());
        }
        return ResponseEntity.ok(resp);
    }

    private static final java.util.regex.Pattern CEDAR_ID = java.util.regex.Pattern.compile("@id\\(\"([^\"]+)\"\\)");

    private static String extractCedarId(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = CEDAR_ID.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static Map<String, Object> refEntry(String kind, String value, boolean known) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("value", value);
        m.put("known", known);
        return m;
    }

    private static String detectEffectPublic(String text) {
        String t = text == null ? "" : text.toLowerCase();
        int f = t.indexOf("forbid"), p = t.indexOf("permit");
        return (f >= 0 && (p < 0 || f < p)) ? "FORBID" : "PERMIT";
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).collect(Collectors.toList());
        }
        if (o instanceof String s && !s.isBlank()) {
            return Arrays.stream(s.split("[,\\s]+")).filter(x -> !x.isBlank()).collect(Collectors.toList());
        }
        return null;
    }

    @PostMapping("/chat")
    public ResponseEntity<PolicyChatResponse> chatGeneratePolicy(@RequestBody PolicyChatRequest request) {
        PolicyChatResponse response = llmService.generatePolicy(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/chat/save")
    public ResponseEntity<Map<String, Object>> chatAndSavePolicy(@RequestBody PolicyChatRequest request) {
        PolicyChatResponse chatResponse = llmService.generatePolicy(request);

        if (!chatResponse.isConversationComplete()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("saved", false);
            response.put("conversationComplete", false);
            response.put("followUpQuestion", chatResponse.getFollowUpQuestion());
            return ResponseEntity.ok(response);
        }

        if (!chatResponse.isSuccess()) {
            return ResponseEntity.badRequest().body(Map.of("error", chatResponse.getError()));
        }

        if (chatResponse.getValidationError() != null) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("saved", false);
            response.put("validationError", chatResponse.getValidationError());
            response.put("generatedPolicy", chatResponse.getPolicyText());
            response.put("explanation", chatResponse.getExplanation());
            return ResponseEntity.ok(response);
        }

        PolicyDto dto = PolicyDto.builder()
                .policyName(chatResponse.getSuggestedName())
                .description(chatResponse.getDescription())
                .policyText(chatResponse.getPolicyText())
                .effect(chatResponse.getEffect())
                .source(chatResponse.getSource())
                .originalPrompt(request.getEffectivePrompt())
                .enabled(true)
                .build();

        PolicyCreationResult result = policyService.createPolicy(dto);
        if (result.success()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("saved", true);
            response.put("policy", toMap(result.policy()));
            response.put("explanation", chatResponse.getExplanation());
            return ResponseEntity.ok(response);
        }

        return ResponseEntity.badRequest().body(Map.of(
                "error", result.error(),
                "generatedPolicy", chatResponse.getPolicyText()
        ));
    }

    private Map<String, Object> toMap(GatewayPolicyEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("policyName", entity.getPolicyName());
        map.put("description", entity.getDescription());
        map.put("cedarPolicyId", entity.getCedarPolicyId());
        map.put("policyText", entity.getPolicyText());
        map.put("effect", entity.getEffect());
        map.put("enabled", entity.getEnabled());
        map.put("priority", entity.getPriority());
        map.put("tags", entity.getTags());
        map.put("source", entity.getSource());
        map.put("principalKind", entity.getPrincipalKind()); // AGENT | AGENT_GROUP | AGENT_TYPE | ANY
        map.put("principalId", entity.getPrincipalId());     // agent/group/type name (null for ANY)
        map.put("originalPrompt", entity.getOriginalPrompt());
        map.put("createdBy", entity.getCreatedBy());
        map.put("createdAt", entity.getCreatedAt());
        map.put("updatedAt", entity.getUpdatedAt());
        return map;
    }
}
