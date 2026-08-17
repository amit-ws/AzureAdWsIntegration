package com.ws.wsAgenticSecurityGateway.postprocessor.controller;

import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ClassificationSummary;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ClassificationView;
import com.ws.wsAgenticSecurityGateway.postprocessor.service.PostProcessorQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    public PostProcessorController(PostProcessorQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Classifications for the current tenant, newest first. Optional filters (most specific wins):
     * {@code correlationId} (one hop), {@code traceId} (a whole journey), {@code producer} (a tool/agent),
     * {@code sensitivity} (PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED); none ⇒ the recent list capped by {@code limit}.
     */
    @GetMapping("/classifications")
    public ResponseEntity<List<ClassificationView>> classifications(
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String sensitivity,
            @RequestParam(required = false) String producer,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(queryService.list(correlationId, traceId, sensitivity, producer, limit));
    }

    /** Header counts (total, per-sensitivity, injection) for the current tenant. */
    @GetMapping("/classifications/summary")
    public ResponseEntity<ClassificationSummary> summary() {
        return ResponseEntity.ok(queryService.summary());
    }
}
