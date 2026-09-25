package com.ws.wsAgenticSecurityGateway.postprocessor.controller;

import com.ws.wsAgenticSecurityGateway.postprocessor.dto.EntitySensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.InsightsReport;
import com.ws.wsAgenticSecurityGateway.postprocessor.service.PostProcessorInsightsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read API for the egress Insights layer — per-capability fingerprints, the sensitive-data-sharing map, and drift
 * alerts, all derived live from the classification table. Tenant-scoped by the admin request context; read-only.
 */
@RestController
@RequestMapping("/api/admin/post-processor")
@Slf4j
public class PostProcessorInsightsController {

    private final PostProcessorInsightsService insightsService;

    public PostProcessorInsightsController(PostProcessorInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    /** Fingerprints + sharing map + drift for the current tenant, computed from the classification history. */
    @GetMapping("/insights")
    public ResponseEntity<InsightsReport> insights() {
        return ResponseEntity.ok(insightsService.insights());
    }

    /** Per-entity peak sensitivity (agents / servers / tools) for badging the Agents + Servers pages. */
    @GetMapping("/insights/entity-sensitivity")
    public ResponseEntity<EntitySensitivity> entitySensitivity() {
        return ResponseEntity.ok(insightsService.entitySensitivity());
    }
}
