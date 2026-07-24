package com.ws.wsAgenticSecurityGateway.pdp.controller;

import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
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

@RestController
@RequestMapping("/api/admin/policies")
@Slf4j
public class PolicyController {

    private final PolicyService policyService;
    private final PolicyLlmService llmService;
    private final CedarPolicyEngine cedarEngine;
    private final GatewayAuditService auditService;

    public PolicyController(PolicyService policyService,
                            PolicyLlmService llmService,
                            CedarPolicyEngine cedarEngine,
                            GatewayAuditService auditService) {
        this.policyService = policyService;
        this.llmService = llmService;
        this.cedarEngine = cedarEngine;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listPolicies() {
        List<GatewayPolicyEntity> policies = policyService.getAllPolicies();
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
        if (valid) {
            return ResponseEntity.ok(Map.of("valid", true));
        }
        return ResponseEntity.ok(Map.of("valid", false, "error", error));
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
        map.put("originalPrompt", entity.getOriginalPrompt());
        map.put("createdBy", entity.getCreatedBy());
        map.put("createdAt", entity.getCreatedAt());
        map.put("updatedAt", entity.getUpdatedAt());
        return map;
    }
}
