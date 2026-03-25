package com.ws.wsAgenticSecurityGateway.audit.controller;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.service.AuditQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * REST controller for the Admin Dashboard audit log page.
 *
 * <p>Provides paginated, filtered audit log queries plus summary
 * statistics and timeline data for the dashboard UI.
 */
@RestController
@RequestMapping("/api/admin/audit")
@Slf4j
public class AuditController {

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    /**
     * GET /api/admin/audit/logs
     * Paginated, multi-field filtered audit log query.
     */
    @GetMapping("/logs")
    public ResponseEntity<Page<McpAuditLog>> getLogs(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String serverName,
            @RequestParam(required = false) String capabilityName,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) String tokenType,
            @RequestParam(required = false) String userIdentity,
            @RequestParam(required = false) String sourceIp,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "timestamp,desc") String sort) {

        log.info("📋 GET /api/admin/audit/logs - page={}, size={}, filters=[module={}, eventType={}, status={}, serverName={}]",
                page, size, module, eventType, status, serverName);

        // Cap page size
        size = Math.min(size, 100);

        // Parse sort
        Sort sortObj = parseSort(sort);
        PageRequest pageRequest = PageRequest.of(page, size, sortObj);

        // Parse enum filters (tolerant — invalid values become null)
        AuditModule moduleEnum = parseEnum(AuditModule.class, module);
        AuditEventType eventTypeEnum = parseEnum(AuditEventType.class, eventType);
        AuditStatus statusEnum = parseEnum(AuditStatus.class, status);
        AuditSeverity severityEnum = parseEnum(AuditSeverity.class, severity);

        Page<McpAuditLog> results = auditQueryService.queryLogs(
                moduleEnum, eventTypeEnum, statusEnum, severityEnum,
                serverName, capabilityName, correlationId, sessionId,
                agentName, tokenType, userIdentity, sourceIp,
                search, fromDate, toDate, pageRequest);
        return ResponseEntity.ok(results);
    }

    /**
     * GET /api/admin/audit/logs/{id}
     * Single audit record with full JSONB payloads.
     */
    @GetMapping("/logs/{id}")
    public ResponseEntity<McpAuditLog> getLogById(@PathVariable UUID id) {
        log.info("📋 GET /api/admin/audit/logs/{}", id);
        return auditQueryService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/admin/audit/logs/correlation/{correlationId}
     * All records sharing a correlation ID (full invocation chain).
     */
    @GetMapping("/logs/correlation/{correlationId}")
    public ResponseEntity<List<McpAuditLog>> getByCorrelationId(@PathVariable String correlationId) {
        log.info("📋 GET /api/admin/audit/logs/correlation/{}", correlationId);
        List<McpAuditLog> records = auditQueryService.getCorrelationChain(correlationId);
        return ResponseEntity.ok(records);
    }

    /**
     * GET /api/admin/audit/stats
     * Summary statistics for the audit dashboard header.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {

        log.info("📊 GET /api/admin/audit/stats");
        Map<String, Object> stats = auditQueryService.getStats(since);
        return ResponseEntity.ok(stats);
    }

    /**
     * GET /api/admin/audit/stats/timeline
     * Event counts bucketed by hour for the timeline chart.
     */
    @GetMapping("/stats/timeline")
    public ResponseEntity<List<Map<String, Object>>> getTimeline(
            @RequestParam(defaultValue = "24") int hours) {

        log.info("📊 GET /api/admin/audit/stats/timeline?hours={}", hours);
        List<Map<String, Object>> timeline = auditQueryService.getTimeline(hours);
        return ResponseEntity.ok(timeline);
    }

    /**
     * GET /api/admin/audit/filters
     * Available filter values for populating UI dropdowns.
     */
    @GetMapping("/filters")
    public ResponseEntity<Map<String, Object>> getFilterValues() {
        log.info("📋 GET /api/admin/audit/filters");
        Map<String, Object> filters = auditQueryService.getFilterValues();
        return ResponseEntity.ok(filters);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Sort parseSort(String sortParam) {
        try {
            String[] parts = sortParam.split(",");
            String field = parts[0].trim();
            Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;
            return Sort.by(dir, field);
        } catch (Exception e) {
            return Sort.by(Sort.Direction.DESC, "timestamp");
        }
    }

    private <T extends Enum<T>> T parseEnum(Class<T> enumClass, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(enumClass, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
