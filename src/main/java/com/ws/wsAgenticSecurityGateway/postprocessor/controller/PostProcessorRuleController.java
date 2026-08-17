package com.ws.wsAgenticSecurityGateway.postprocessor.controller;

import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DataTagRuleDto;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DetectorInfo;
import com.ws.wsAgenticSecurityGateway.postprocessor.service.PostProcessorRuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public PostProcessorRuleController(PostProcessorRuleService ruleService) {
        this.ruleService = ruleService;
    }

    /** The built-in detectors an admin can DISABLE / OVERRIDE — targets for the rule-authoring UI. */
    @GetMapping("/detectors")
    public ResponseEntity<List<DetectorInfo>> detectors() {
        return ResponseEntity.ok(ruleService.detectors());
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

    private static ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
