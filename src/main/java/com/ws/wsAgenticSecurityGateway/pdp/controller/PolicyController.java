package com.ws.wsAgenticSecurityGateway.pdp.controller;

import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyChatRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyChatResponse;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyDto;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyLlmService;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyService;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyService.PolicyCreationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for PDP (Policy Decision Point) management.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/admin/policies}          — List all policies</li>
 *   <li>{@code POST   /api/admin/policies}          — Create a new policy</li>
 *   <li>{@code GET    /api/admin/policies/{id}}      — Get a specific policy</li>
 *   <li>{@code PUT    /api/admin/policies/{id}}      — Update a policy</li>
 *   <li>{@code DELETE /api/admin/policies/{id}}      — Delete a policy</li>
 *   <li>{@code POST   /api/admin/policies/{id}/toggle} — Toggle enabled/disabled</li>
 *   <li>{@code POST   /api/admin/policies/reload}    — Force reload Cedar engine</li>
 *   <li>{@code GET    /api/admin/policies/stats}      — Policy statistics</li>
 *   <li>{@code POST   /api/admin/policies/validate}   — Validate Cedar syntax</li>
 *   <li>{@code POST   /api/admin/policies/chat}       — Chatbot: NL → Cedar policy</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/policies")
@Slf4j
public class PolicyController {

    private final PolicyService policyService;
    private final PolicyLlmService llmService;
    private final CedarPolicyEngine cedarEngine;
    private final McpAuditService auditService;

    public PolicyController(PolicyService policyService,
                            PolicyLlmService llmService,
                            CedarPolicyEngine cedarEngine,
                            McpAuditService auditService) {
        this.policyService = policyService;
        this.llmService = llmService;
        this.cedarEngine = cedarEngine;
        this.auditService = auditService;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CRUD
    // ════════════════════════════════════════════════════════════════════

    /** List all policies. */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listPolicies() {
        List<GatewayPolicyEntity> policies = policyService.getAllPolicies();
        List<Map<String, Object>> result = policies.stream()
                .map(this::toMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** Get a specific policy by ID. */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getPolicy(@PathVariable UUID id) {
        return policyService.getById(id)
                .map(p -> ResponseEntity.ok(toMap(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Create a new policy. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPolicy(@RequestBody PolicyDto dto) {
        PolicyCreationResult result = policyService.createPolicy(dto);
        if (result.success()) {
            return ResponseEntity.ok(toMap(result.policy()));
        }
        return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }

    /** Update an existing policy. */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updatePolicy(@PathVariable UUID id,
                                                              @RequestBody PolicyDto dto) {
        PolicyCreationResult result = policyService.updatePolicy(id, dto);
        if (result.success()) {
            return ResponseEntity.ok(toMap(result.policy()));
        }
        return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }

    /** Delete a policy. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deletePolicy(@PathVariable UUID id) {
        if (policyService.deletePolicy(id)) {
            return ResponseEntity.ok(Map.of("deleted", true, "id", id.toString()));
        }
        return ResponseEntity.notFound().build();
    }

    /** Toggle a policy's enabled/disabled state. */
    @PostMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> togglePolicy(@PathVariable UUID id) {
        return policyService.toggleEnabled(id)
                .map(p -> ResponseEntity.ok(toMap(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ════════════════════════════════════════════════════════════════════
    //  ENGINE OPERATIONS
    // ════════════════════════════════════════════════════════════════════

    /** Force reload all policies into the Cedar engine. */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadPolicies() {
        int count = policyService.reloadEngine();
        return ResponseEntity.ok(Map.of(
                "reloaded", count >= 0,
                "policyCount", Math.max(count, 0),
                "error", count < 0 ? "Failed to parse policies" : ""
        ));
    }

    /** Get policy statistics. */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = policyService.getStats();
        stats.put("llmAvailable", llmService.isLlmAvailable());
        return ResponseEntity.ok(stats);
    }

    /** Validate Cedar policy syntax without saving. */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validatePolicy(@RequestBody Map<String, String> body) {
        String policyText = body.get("policyText");
        if (policyText == null || policyText.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", "policyText is required"));
        }
        String error = cedarEngine.validatePolicy(policyText);
        boolean valid = (error == null);
        auditService.auditPdpPolicyValidated(policyText, valid, error);
        if (valid) {
            return ResponseEntity.ok(Map.of("valid", true));
        }
        return ResponseEntity.ok(Map.of("valid", false, "error", error));
    }

    // ════════════════════════════════════════════════════════════════════
    //  CHATBOT — Natural Language → Cedar Policy
    // ════════════════════════════════════════════════════════════════════

    /**
     * Generate a Cedar policy from natural language via LLM (multi-turn supported).
     *
     * <p>Supports two modes:
     * <ul>
     *   <li><b>Single-shot</b>: Send {@code {"prompt": "..."}} — generates in one call</li>
     *   <li><b>Multi-turn</b>: Send {@code {"messages": [...]}} — iterative refinement</li>
     * </ul>
     *
     * <p>When the LLM needs more information, the response will have
     * {@code conversationComplete=false} and a {@code followUpQuestion}.
     * The UI should then re-send the full conversation history with the user's answer appended.
     */
    @PostMapping("/chat")
    public ResponseEntity<PolicyChatResponse> chatGeneratePolicy(@RequestBody PolicyChatRequest request) {
        PolicyChatResponse response = llmService.generatePolicy(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Generate AND save a Cedar policy from natural language.
     * Only saves when the conversation is complete (policy generated).
     *
     * <p>If the LLM returns a follow-up question, this endpoint returns it
     * without saving — the UI should continue the conversation via {@code /chat}
     * and only call {@code /chat/save} once the policy is finalized.
     */
    @PostMapping("/chat/save")
    public ResponseEntity<Map<String, Object>> chatAndSavePolicy(@RequestBody PolicyChatRequest request) {
        // Step 1: Generate via LLM (multi-turn aware)
        PolicyChatResponse chatResponse = llmService.generatePolicy(request);

        // If conversation is not complete, return follow-up — don't save
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

        // Step 2: Check validation errors
        if (chatResponse.getValidationError() != null) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("saved", false);
            response.put("validationError", chatResponse.getValidationError());
            response.put("generatedPolicy", chatResponse.getPolicyText());
            response.put("explanation", chatResponse.getExplanation());
            return ResponseEntity.ok(response);
        }

        // Step 3: Create policy from LLM output
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

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

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
        map.put("originalPrompt", entity.getOriginalPrompt());
        map.put("createdBy", entity.getCreatedBy());
        map.put("createdAt", entity.getCreatedAt());
        map.put("updatedAt", entity.getUpdatedAt());
        return map;
    }
}
