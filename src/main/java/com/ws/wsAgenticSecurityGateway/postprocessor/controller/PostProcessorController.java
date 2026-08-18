package com.ws.wsAgenticSecurityGateway.postprocessor.controller;

import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ClassificationSummary;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ClassificationView;
import com.ws.wsAgenticSecurityGateway.postprocessor.service.PostProcessorQueryService;
import com.ws.wsAgenticSecurityGateway.postprocessor.service.PostProcessorReprocessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read API for the egress post-processor's Processed-Data view. Serves the classifications the gateway recorded as
 * governed responses flowed back — the metadata-only tags (categories, sensitivity, detectors, injection), never
 * the raw payload. Tenant-scoped by the admin request context; read-only.
 *
 * <p>The same list endpoint powers the dashboard's jump links: {@code ?correlationId=} from an audit event, and
 * {@code ?traceId=} from a chain / DAG view.
 */
@RestController
@RequestMapping("/api/admin/post-processor")
@Slf4j
public class PostProcessorController {

    private final PostProcessorQueryService queryService;
    private final PostProcessorReprocessService reprocessService;

    public PostProcessorController(PostProcessorQueryService queryService,
                                   PostProcessorReprocessService reprocessService) {
        this.queryService = queryService;
        this.reprocessService = reprocessService;
    }

    /**
     * Classifications for the current tenant, newest first. A {@code correlationId} (one hop) or {@code traceId}
     * (a whole journey) is the jump shortcut and wins if present. Otherwise the multi-select facets apply, each
     * repeatable and combined AND across facets / OR within one: {@code sensitivity}, {@code category},
     * {@code capabilityType}, {@code protocol}, {@code producer}. No facet ⇒ the recent list capped by {@code limit}.
     */
    @GetMapping("/classifications")
    public ResponseEntity<List<ClassificationView>> classifications(
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) List<String> sensitivity,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) List<String> capabilityType,
            @RequestParam(required = false) List<String> protocol,
            @RequestParam(required = false) List<String> producer,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(queryService.list(correlationId, traceId, sensitivity, category,
                capabilityType, protocol, producer, limit));
    }

    /** Header counts (total, per-sensitivity, injection) for the current tenant. */
    @GetMapping("/classifications/summary")
    public ResponseEntity<ClassificationSummary> summary() {
        return ResponseEntity.ok(queryService.summary());
    }

    /** The filter dropdowns' choices for the Processed-Data view, served from the backend (distinct tenant values). */
    @GetMapping("/classifications/filters")
    public ResponseEntity<Map<String, List<String>>> filters() {
        return ResponseEntity.ok(queryService.filterOptions());
    }

    /**
     * Re-classify one hop from its retained raw response (in the audit trail) against the current rules — used to
     * fill a "not scanned" gap or re-tag after a rule change. 400 if no raw was retained for that hop.
     */
    @PostMapping("/classifications/reprocess")
    public ResponseEntity<?> reprocess(@RequestParam String correlationId) {
        try {
            return ResponseEntity.ok(reprocessService.reprocess(correlationId));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Re-classify a chosen set of hops (by correlation id) against the current rules — the "reprocess selected" path. */
    @PostMapping("/classifications/reprocess-batch")
    public ResponseEntity<Map<String, Object>> reprocessBatch(@RequestBody List<String> correlationIds) {
        return ResponseEntity.ok(reprocessService.reprocessMany(correlationIds));
    }

    /** Re-classify every hop in a trace (whole journey) against the current rules. */
    @PostMapping("/classifications/reprocess-trace")
    public ResponseEntity<Map<String, Object>> reprocessTrace(@RequestParam String traceId) {
        return ResponseEntity.ok(reprocessService.reprocessTrace(traceId));
    }

    /** Re-classify all of the tenant's recorded classifications against the current rules (page-capped). */
    @PostMapping("/classifications/reprocess-all")
    public ResponseEntity<Map<String, Object>> reprocessAll() {
        return ResponseEntity.ok(reprocessService.reprocessAll());
    }
}
