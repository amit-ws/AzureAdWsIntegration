package com.ws.wsAgenticSecurityGateway.compliance.repository;

import com.ws.wsAgenticSecurityGateway.audit.entity.GatewayAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Compliance-owned aggregate over the foundational {@code gateway_audit_log}. Carried in this package so the
 * compliance module is self-contained (independent of the CISO dashboard). Native SQL — the bound entity
 * ({@link GatewayAuditLog}) is only a formality for Spring Data.
 */
@Repository
public interface ComplianceAuditStatsRepository extends JpaRepository<GatewayAuditLog, UUID> {

    /**
     * Tenant-level audit coverage for the evidence pack (logging / monitoring controls). Single row:
     * {@code [total_events, event_types, human_attributed, distinct_humans, distinct_agents, first_event, last_event]}
     * — the five counts are Long, the last two are Timestamp.
     */
    @Query(value = """
            SELECT COUNT(*) AS total_events,
                   COUNT(DISTINCT event_type) AS event_types,
                   COUNT(*) FILTER (WHERE human_user_id IS NOT NULL) AS human_attributed,
                   COUNT(DISTINCT human_user_id) AS distinct_humans,
                   COUNT(DISTINCT agent_name) AS distinct_agents,
                   MIN(timestamp) AS first_event,
                   MAX(timestamp) AS last_event
            FROM gateway_audit_log
            WHERE ws_tenant_name = :tenant
            """, nativeQuery = true)
    List<Object[]> tenantAuditStats(@Param("tenant") String tenant);
}
