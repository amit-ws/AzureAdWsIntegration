package com.ws.wsAgenticSecurityGateway.postprocessor.controller;

import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DataTagRuleDto;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DetectorInfo;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.EffectiveDetectorView;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.RuleChatRequest;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.RuleChatResponse;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.RuleTemplatePackView;
import com.ws.wsAgenticSecurityGateway.postprocessor.service.PostProcessorRuleService;
import com.ws.wsAgenticSecurityGateway.postprocessor.service.RuleAssistantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API for egress-classifier rules — the "admins override/disable defaults and set custom rules" half of the
 * post-processor. Rules layer on the built-in detectors: CUSTOM adds a regex detector, DISABLE turns a built-in
 * off, OVERRIDE remaps a built-in's category/sensitivity. Tenant-scoped by the admin request context; every write
 * takes effect on the next classified response.
 */
@RestController
@RequestMapping("/api/admin/post-processor")
@Slf4j
public class PostProcessorRuleController {

    private final PostProcessorRuleService ruleService;
    private final RuleAssistantService assistantService;

    public PostProcessorRuleController(PostProcessorRuleService ruleService, RuleAssistantService assistantService) {
        this.ruleService = ruleService;
        this.assistantService = assistantService;
    }

    /** The built-in detectors an admin can DISABLE / OVERRIDE — targets for the rule-authoring UI. */
    @GetMapping("/detectors")
    public ResponseEntity<List<DetectorInfo>> detectors() {
        return ResponseEntity.ok(ruleService.detectors());
    }

    /** Every built-in detector with its effective state for this tenant (on/off + any category/sensitivity remap). */
    @GetMapping("/detectors/effective")
    public ResponseEntity<List<EffectiveDetectorView>> effectiveDetectors() {
        return ResponseEntity.ok(ruleService.effectiveDetectors());
    }

    /** Turn a built-in detector on/off for this tenant. Body: {@code {"enabled": true|false}}. */
    @PostMapping("/detectors/{matcher}/enabled")
    public ResponseEntity<?> setDetectorEnabled(@PathVariable String matcher, @RequestBody Map<String, Boolean> body) {
        try {
            ruleService.setDetectorEnabled(matcher, Boolean.TRUE.equals(body.get("enabled")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /** Remap a built-in detector's category/sensitivity for this tenant (reuses the rule DTO's category + sensitivity). */
    @PutMapping("/detectors/{matcher}/override")
    public ResponseEntity<?> setDetectorOverride(@PathVariable String matcher, @RequestBody DataTagRuleDto body) {
        try {
            ruleService.setDetectorOverride(matcher, body.dataCategories(), body.sensitivity());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /** Clear a built-in detector's override (revert to its shipped category/sensitivity). */
    @DeleteMapping("/detectors/{matcher}/override")
    public ResponseEntity<?> clearDetectorOverride(@PathVariable String matcher) {
        try {
            ruleService.clearDetectorOverride(matcher);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @GetMapping("/rules")
    public ResponseEntity<List<DataTagRuleDto>> list() {
        return ResponseEntity.ok(ruleService.list());
    }

    @PostMapping("/rules")
    public ResponseEntity<?> create(@RequestBody DataTagRuleDto body) {
        try {
            return ResponseEntity.ok(ruleService.create(body));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody DataTagRuleDto body) {
        try {
            return ResponseEntity.ok(ruleService.update(id, body));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PostMapping("/rules/{id}/toggle")
    public ResponseEntity<?> toggle(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(ruleService.toggle(id));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        try {
            ruleService.delete(id);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    // ── Industry rule-template library ──

    /** The shipped template packs, each template marked installed/not for this tenant. */
    @GetMapping("/rules/templates")
    public ResponseEntity<List<RuleTemplatePackView>> templates() {
        return ResponseEntity.ok(ruleService.templates());
    }

    /** Install one template as a normal editable rule (idempotent). Returns the installed rule. */
    @PostMapping("/rules/templates/{templateId}/install")
    public ResponseEntity<?> installTemplate(@PathVariable String templateId) {
        try {
            return ResponseEntity.ok(ruleService.installTemplate(templateId));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /** Install every not-yet-installed template in an industry pack. Returns a per-pack install summary. */
    @PostMapping("/rules/templates/packs/install")
    public ResponseEntity<?> installPack(@RequestParam String industry) {
        try {
            return ResponseEntity.ok(ruleService.installPack(industry));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    // ── AI rule assistant (mirrors /policies/chat) ──

    /** Draft a custom rule from a natural-language description, or ask a follow-up. Never saves — the admin does. */
    @PostMapping("/rules/chat")
    public ResponseEntity<RuleChatResponse> ruleChat(@RequestBody RuleChatRequest body) {
        return ResponseEntity.ok(assistantService.chat(body));
    }

    /** Whether the AI assistant is configured — the FE shows an "AI Active" badge / hides the assistant otherwise. */
    @GetMapping("/rules/chat/status")
    public ResponseEntity<Map<String, Object>> ruleChatStatus() {
        return ResponseEntity.ok(Map.of("llmAvailable", assistantService.isLlmAvailable()));
    }

    /** Starter prompt suggestions grounded in THIS tenant's actual classified data (empty if the assistant is off). */
    @GetMapping("/rules/chat/suggestions")
    public ResponseEntity<Map<String, Object>> ruleChatSuggestions() {
        return ResponseEntity.ok(Map.of("suggestions", assistantService.suggestions()));
    }

    private static ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
