package com.ws.wsAgenticSecurityGateway.audit.repository;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;

/**
 * Dynamic specification builder for {@link McpAuditLog} queries.
 *
 * <p>Composes optional filter predicates via AND-composition, enabling
 * a single REST endpoint to handle any combination of filters without
 * a combinatorial explosion of repository finder methods.
 */
public class McpAuditLogSpecification {

    private McpAuditLogSpecification() {
        // static utility
    }

    /**
     * Build a composed specification from optional filter parameters.
     * Any {@code null} parameter is silently skipped.
     */
    public static Specification<McpAuditLog> build(
            AuditModule module,
            AuditEventType eventType,
            AuditStatus status,
            AuditSeverity severity,
            String serverName,
            String capabilityName,
            String correlationId,
            String sessionId,
            String agentName,
            String searchText,
            LocalDateTime fromDate,
            LocalDateTime toDate) {

        Specification<McpAuditLog> spec = Specification.where(null);

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
        if (sessionId != null && !sessionId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("sessionId"), sessionId));
        }
        if (agentName != null && !agentName.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("agentName"), agentName));
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
                    cb.like(cb.lower(root.get("sessionId")), lower),
                    cb.like(cb.lower(root.get("capabilityName")), lower),
                    cb.like(cb.lower(root.get("errorMessage")), lower),
                    cb.like(cb.lower(root.get("serverName")), lower),
                    cb.like(cb.lower(root.get("agentName")), lower)
            ));
        }

        return spec;
    }
}
