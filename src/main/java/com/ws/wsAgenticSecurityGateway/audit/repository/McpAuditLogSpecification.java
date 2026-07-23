package com.ws.wsAgenticSecurityGateway.audit.repository;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;

public class McpAuditLogSpecification {

    private McpAuditLogSpecification() {
    }

    public static Specification<McpAuditLog> build(
            AuditModule module,
            AuditEventType eventType,
            AuditStatus status,
            AuditSeverity severity,
            String serverName,
            String capabilityName,
            String correlationId,
            String traceId,
            String sessionId,
            String agentName,
            String tokenType,
            String userIdentity,
            String sourceIp,
            String searchText,
            LocalDateTime fromDate,
            LocalDateTime toDate) {
        return build(module, eventType, status, severity, serverName, capabilityName,
                correlationId, traceId, sessionId, agentName, tokenType, userIdentity, sourceIp,
                searchText, fromDate, toDate, null);
    }

    public static Specification<McpAuditLog> build(
            AuditModule module,
            AuditEventType eventType,
            AuditStatus status,
            AuditSeverity severity,
            String serverName,
            String capabilityName,
            String correlationId,
            String traceId,
            String sessionId,
            String agentName,
            String tokenType,
            String userIdentity,
            String sourceIp,
            String searchText,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            String wsTenantName) {

        Specification<McpAuditLog> spec = Specification.where(null);

        if (wsTenantName != null && !wsTenantName.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("wsTenantName"), wsTenantName));
        }

        if (module != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("module"), module));
        }
        if (eventType != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("eventType"), eventType));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (severity != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("severity"), severity));
        }
        if (serverName != null && !serverName.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("serverName"), serverName));
        }
        if (capabilityName != null && !capabilityName.isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("capabilityName")),
                            "%" + capabilityName.toLowerCase() + "%"));
        }
        if (correlationId != null && !correlationId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("correlationId"), correlationId));
        }
        if (traceId != null && !traceId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("traceId"), traceId));
        }
        if (sessionId != null && !sessionId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("sessionId"), sessionId));
        }
        if (agentName != null && !agentName.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("agentName"), agentName));
        }
        if (tokenType != null && !tokenType.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("tokenType"), tokenType));
        }
        if (userIdentity != null && !userIdentity.isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("userIdentity")),
                            "%" + userIdentity.toLowerCase() + "%"));
        }
        if (sourceIp != null && !sourceIp.isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(root.get("sourceIp"), sourceIp + "%"));
        }
        if (fromDate != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("timestamp"), fromDate));
        }
        if (toDate != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("timestamp"), toDate));
        }
        if (searchText != null && !searchText.isBlank()) {
            String lower = "%" + searchText.toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("correlationId")), lower),
                    cb.like(cb.lower(root.get("traceId")), lower),
                    cb.like(cb.lower(root.get("sessionId")), lower),
                    cb.like(cb.lower(root.get("capabilityName")), lower),
                    cb.like(cb.lower(root.get("errorMessage")), lower),
                    cb.like(cb.lower(root.get("serverName")), lower),
                    cb.like(cb.lower(root.get("agentName")), lower),
                    cb.like(cb.lower(root.get("userIdentity")), lower),
                    cb.like(cb.lower(root.get("agentClientId")), lower),
                    cb.like(cb.lower(root.get("sourceIp")), lower)
            ));
        }

        return spec;
    }
}
