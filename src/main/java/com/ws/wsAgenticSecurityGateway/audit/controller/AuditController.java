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

@RestController
@RequestMapping("/api/admin/audit")
@Slf4j
public class AuditController {

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping("/logs")
    public ResponseEntity<Page<McpAuditLog>> getLogs(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String serverName,
            @RequestParam(required = false) String capabilityName,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String traceId,
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

 log.info("GET /api/admin/audit/logs - page={}, size={}, filters=[module={}, eventType={}, status={}, serverName={}]",
                page, size, module, eventType, status, serverName);

        size = Math.min(size, 100);

        Sort sortObj = parseSort(sort);
        PageRequest pageRequest = PageRequest.of(page, size, sortObj);

        AuditModule moduleEnum = parseEnum(AuditModule.class, module);
        AuditEventType eventTypeEnum = parseEnum(AuditEventType.class, eventType);
        AuditStatus statusEnum = parseEnum(AuditStatus.class, status);
        AuditSeverity severityEnum = parseEnum(AuditSeverity.class, severity);

        Page<McpAuditLog> results = auditQueryService.queryLogs(
                moduleEnum, eventTypeEnum, statusEnum, severityEnum,
                serverName, capabilityName, correlationId, traceId, sessionId,
                agentName, tokenType, userIdentity, sourceIp,
                search, fromDate, toDate, pageRequest);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/logs/{id}")
    public ResponseEntity<McpAuditLog> getLogById(@PathVariable UUID id) {
 log.info("GET /api/admin/audit/logs/{}", id);
        return auditQueryService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/logs/correlation/{correlationId}")
    public ResponseEntity<List<McpAuditLog>> getByCorrelationId(@PathVariable String correlationId) {
 log.info("GET /api/admin/audit/logs/correlation/{}", correlationId);
        List<McpAuditLog> records = auditQueryService.getCorrelationChain(correlationId);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/logs/trace/{traceId}")
    public ResponseEntity<List<McpAuditLog>> getByTraceId(@PathVariable String traceId) {
 log.info("GET /api/admin/audit/logs/trace/{}", traceId);
        List<McpAuditLog> records = auditQueryService.getTraceChain(traceId);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {

 log.info("GET /api/admin/audit/stats");
        Map<String, Object> stats = auditQueryService.getStats(since);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/stats/timeline")
    public ResponseEntity<List<Map<String, Object>>> getTimeline(
            @RequestParam(defaultValue = "24") int hours) {

 log.info("GET /api/admin/audit/stats/timeline?hours={}", hours);
        List<Map<String, Object>> timeline = auditQueryService.getTimeline(hours);
        return ResponseEntity.ok(timeline);
    }

    @GetMapping("/filters")
    public ResponseEntity<Map<String, Object>> getFilterValues() {
 log.info("GET /api/admin/audit/filters");
        Map<String, Object> filters = auditQueryService.getFilterValues();
        return ResponseEntity.ok(filters);
    }

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
