package com.ws.wsAgenticSecurityGateway.audit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.dto.AgentActivity;
import com.ws.wsAgenticSecurityGateway.audit.dto.IdentityGraph;
import com.ws.wsAgenticSecurityGateway.audit.entity.GatewayAuditLog;
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
    public ResponseEntity<Page<GatewayAuditLog>> getLogs(
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
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String capabilityType,
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

        Page<GatewayAuditLog> results = auditQueryService.queryLogs(
                moduleEnum, eventTypeEnum, statusEnum, severityEnum,
                serverName, capabilityName, correlationId, traceId, sessionId,
                agentName, tokenType, userIdentity, sourceIp,
                search, fromDate, toDate, protocol, capabilityType, pageRequest);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/logs/{id}")
    public ResponseEntity<GatewayAuditLog> getLogById(@PathVariable UUID id) {
 log.info("GET /api/admin/audit/logs/{}", id);
        return auditQueryService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/logs/correlation/{correlationId}")
    public ResponseEntity<List<GatewayAuditLog>> getByCorrelationId(@PathVariable String correlationId) {
 log.info("GET /api/admin/audit/logs/correlation/{}", correlationId);
        List<GatewayAuditLog> records = auditQueryService.getCorrelationChain(correlationId);
        return ResponseEntity.ok(records);
    }

    /** The full OBO token receipt (jti, aud, scope, ttl, act_chain, …) for a leg's minted delegation token. */
    @GetMapping("/logs/correlation/{correlationId}/obo-receipt")
    public ResponseEntity<JsonNode> getOboReceipt(@PathVariable String correlationId) {
        log.info("GET /api/admin/audit/logs/correlation/{}/obo-receipt", correlationId);
        return auditQueryService.getOboReceipt(correlationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/logs/trace/{traceId}")
    public ResponseEntity<List<GatewayAuditLog>> getByTraceId(@PathVariable String traceId) {
 log.info("GET /api/admin/audit/logs/trace/{}", traceId);
        List<GatewayAuditLog> records = auditQueryService.getTraceChain(traceId);
        return ResponseEntity.ok(records);
    }

    /**
     * Per-request "activities" for one agent (matched by OAuth client id or principal name), newest first.
     * The transport-agnostic view the dashboard shows in place of sessions — works for stateless requests
     * (no session row) and names the human each request acted for.
     */
    @GetMapping("/logs/activities")
    public ResponseEntity<List<AgentActivity>> getAgentActivities(
            @RequestParam String agentKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
 log.info("GET /api/admin/audit/logs/activities agentKey={} page={} size={}", agentKey, page, size);
        return ResponseEntity.ok(auditQueryService.getAgentActivities(agentKey, page, size));
    }

    /**
     * The identity graph — human → agent → tool relationships (with allow/deny) rolled up from the audit
     * trail. Read-only, tenant-scoped, optionally windowed by {@code hours} (omit for all-time).
     */
    @GetMapping("/graph")
    public ResponseEntity<IdentityGraph> getIdentityGraph(
            @RequestParam(required = false) Integer hours) {
 log.info("GET /api/admin/audit/graph hours={}", hours);
        return ResponseEntity.ok(auditQueryService.getIdentityGraph(hours));
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
