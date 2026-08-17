package com.ws.wsAgenticSecurityGateway.compliance.repository;

import com.ws.wsAgenticSecurityGateway.audit.entity.PdpAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Compliance-owned aggregate over the foundational PDP decision ledger ({@code pdp_audit_log}). The compliance
 * module carries its OWN copy of this query so the package ships independently of the CISO dashboard. The query is
 * native SQL — the bound entity ({@link PdpAuditLog}) is only a formality for Spring Data.
 */
@Repository
public interface ComplianceDecisionRepository extends JpaRepository<PdpAuditLog, UUID> {

    /**
     * Tenant decision coverage: total decisions, allow/deny split, and how many were attributed to a specific
     * policy vs not (default-deny / no matching permit). Single row:
     * {@code [total_decisions, allows, denies, attributed, unattributed]} — all Long.
     */
    @Query(value = """
            SELECT COUNT(*) AS total_decisions,
                   COUNT(*) FILTER (WHERE pdp_decision = 'ALLOW') AS allows,
                   COUNT(*) FILTER (WHERE pdp_decision = 'DENY') AS denies,
                   COUNT(*) FILTER (WHERE pdp_policy_id IS NOT NULL) AS attributed,
                   COUNT(*) FILTER (WHERE pdp_policy_id IS NULL) AS unattributed
            FROM pdp_audit_log
            WHERE event_type = 'PDP_DECISION_RENDERED' AND ws_tenant_name = :tenant
            """, nativeQuery = true)
    List<Object[]> policyDecisionCoverage(@Param("tenant") String tenant);
}
