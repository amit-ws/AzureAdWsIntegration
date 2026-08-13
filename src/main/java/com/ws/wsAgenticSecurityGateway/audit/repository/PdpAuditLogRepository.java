package com.ws.wsAgenticSecurityGateway.audit.repository;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.PdpAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PdpAuditLogRepository extends JpaRepository<PdpAuditLog, UUID> {

    List<PdpAuditLog> findByCorrelationId(String correlationId);

    Page<PdpAuditLog> findByPdpSubject(String pdpSubject, Pageable pageable);

    Page<PdpAuditLog> findByPdpResource(String pdpResource, Pageable pageable);

    Page<PdpAuditLog> findByPdpDecision(String pdpDecision, Pageable pageable);

    Page<PdpAuditLog> findByStatus(AuditStatus status, Pageable pageable);

    Page<PdpAuditLog> findByEventType(AuditEventType eventType, Pageable pageable);

    Page<PdpAuditLog> findByTimestampBetween(LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<PdpAuditLog> findByPdpSubjectAndTimestampBetween(
            String pdpSubject, LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<PdpAuditLog> findByPdpDecisionAndTimestampBetween(
            String pdpDecision, LocalDateTime from, LocalDateTime to, Pageable pageable);

    long countByPdpDecision(String pdpDecision);

    long countByPdpDecisionAndTimestampBetween(String pdpDecision, LocalDateTime from, LocalDateTime to);

    List<PdpAuditLog> findByCorrelationIdAndWsTenantName(String correlationId, String wsTenantName);

    Page<PdpAuditLog> findByPdpSubjectAndWsTenantName(String pdpSubject, String wsTenantName, Pageable pageable);

    Page<PdpAuditLog> findByPdpResourceAndWsTenantName(String pdpResource, String wsTenantName, Pageable pageable);

    Page<PdpAuditLog> findByPdpDecisionAndWsTenantName(String pdpDecision, String wsTenantName, Pageable pageable);

    Page<PdpAuditLog> findByStatusAndWsTenantName(AuditStatus status, String wsTenantName, Pageable pageable);

    Page<PdpAuditLog> findByEventTypeAndWsTenantName(AuditEventType eventType, String wsTenantName, Pageable pageable);

    Page<PdpAuditLog> findByTimestampBetweenAndWsTenantName(LocalDateTime from, LocalDateTime to, String wsTenantName, Pageable pageable);

    Page<PdpAuditLog> findByPdpSubjectAndTimestampBetweenAndWsTenantName(
            String pdpSubject, LocalDateTime from, LocalDateTime to, String wsTenantName, Pageable pageable);

    Page<PdpAuditLog> findByPdpDecisionAndTimestampBetweenAndWsTenantName(
            String pdpDecision, LocalDateTime from, LocalDateTime to, String wsTenantName, Pageable pageable);

    long countByPdpDecisionAndWsTenantName(String pdpDecision, String wsTenantName);

    long countByPdpDecisionAndTimestampBetweenAndWsTenantName(String pdpDecision, LocalDateTime from, LocalDateTime to, String wsTenantName);

    /**
     * Per-policy decision activity for the CISO Policy-Activity view, aggregated DB-side.
     * {@code pdp_policy_id} can hold a comma-separated list when several policies co-decide, so it is split
     * ({@code unnest}) and each real policy id counted independently — a naive {@code GROUP BY pdp_policy_id}
     * would create bogus combined buckets. Synthetic markers (e.g. {@code DEFAULT_DENY}) fall away via the join
     * to real {@code cedar_policy_id}s. Only {@code PDP_DECISION_RENDERED} rows carry a decision (ALLOW/DENY).
     * Row shape: {@code [policy_id (String), evaluations (Long), allows (Long), denies (Long), last_fired (Timestamp)]}.
     */
    @Query(value = """
            SELECT trim(raw.pid) AS policy_id,
                   COUNT(*) AS evaluations,
                   COUNT(*) FILTER (WHERE a.pdp_decision = 'ALLOW') AS allows,
                   COUNT(*) FILTER (WHERE a.pdp_decision = 'DENY') AS denies,
                   MAX(a.timestamp) AS last_fired
            FROM pdp_audit_log a
            CROSS JOIN LATERAL unnest(string_to_array(a.pdp_policy_id, ',')) AS raw(pid)
            WHERE a.event_type = 'PDP_DECISION_RENDERED'
              AND a.ws_tenant_name = :tenant
              AND a.pdp_policy_id IS NOT NULL
              AND trim(raw.pid) IN (SELECT cedar_policy_id FROM gateway_policy WHERE ws_tenant_name = :tenant)
            GROUP BY trim(raw.pid)
            """, nativeQuery = true)
    List<Object[]> aggregatePolicyActivity(@Param("tenant") String tenant);

    /**
     * Tenant decision coverage: how many decisions were rendered, their allow/deny split, and how many were
     * attributed to a specific policy vs not (default-deny / no matching permit). Single row:
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

    /**
     * The resources an agent has ACTUALLY reached (allowed calls) — the ground-truth "used" side of the CISO
     * Blast-Radius, complete and clean (100% of decision rows carry a subject/resource). Row shape:
     * {@code [resource_id (String), action (String: toolCall|skillInvocation), uses (Long), last_used (Timestamp)]}.
     */
    @Query(value = """
            SELECT pdp_resource AS resource_id,
                   MAX(pdp_action) AS action,
                   COUNT(*) AS uses,
                   MAX(timestamp) AS last_used
            FROM pdp_audit_log
            WHERE event_type = 'PDP_DECISION_RENDERED' AND pdp_decision = 'ALLOW'
              AND ws_tenant_name = :tenant AND pdp_subject = :subject
            GROUP BY pdp_resource
            """, nativeQuery = true)
    List<Object[]> actualReachBySubject(@Param("tenant") String tenant, @Param("subject") String subject);

    /**
     * The distinct policy ids that have decided for an agent, recovered from the audit — this is how the
     * Blast-Radius picks up GROUP-scoped grants the per-agent principal read-model can't resolve: if a group
     * policy ever allowed/denied this agent, it appears here (evidence of membership). Multi-policy ids are
     * split ({@code unnest}). Observational: a group never exercised won't appear.
     */
    @Query(value = """
            SELECT DISTINCT trim(raw.pid)
            FROM pdp_audit_log a
            CROSS JOIN LATERAL unnest(string_to_array(a.pdp_policy_id, ',')) AS raw(pid)
            WHERE a.event_type = 'PDP_DECISION_RENDERED'
              AND a.ws_tenant_name = :tenant AND a.pdp_subject = :subject AND a.pdp_policy_id IS NOT NULL
            """, nativeQuery = true)
    List<String> policyIdsThatDecidedFor(@Param("tenant") String tenant, @Param("subject") String subject);

    /**
     * The Agent Activity Trail OUTBOUND leg — everything the agent itself did (it is the decision subject). Newest
     * first, bounded. Row shape:
     * {@code [timestamp (Timestamp), action, resource, decision, policy_id, correlation_id, subject]}.
     */
    @Query(value = """
            SELECT timestamp, pdp_action, pdp_resource, pdp_decision, pdp_policy_id, correlation_id, pdp_subject
            FROM pdp_audit_log
            WHERE event_type = 'PDP_DECISION_RENDERED' AND ws_tenant_name = :tenant AND pdp_subject = :agent
            ORDER BY timestamp DESC
            LIMIT :max
            """, nativeQuery = true)
    List<Object[]> agentOutboundActivity(@Param("tenant") String tenant, @Param("agent") String agent,
                                         @Param("max") int max);

    /**
     * The Agent Activity Trail INBOUND leg — everything done <em>to</em> the agent's own skills (someone invoked
     * {@code <agent>.<skill>}), i.e. work requested of it. Match with {@code :prefix} = {@code "<agent>.%"}.
     * Same row shape as {@link #agentOutboundActivity} — here {@code subject} is the caller.
     */
    @Query(value = """
            SELECT timestamp, pdp_action, pdp_resource, pdp_decision, pdp_policy_id, correlation_id, pdp_subject
            FROM pdp_audit_log
            WHERE event_type = 'PDP_DECISION_RENDERED' AND ws_tenant_name = :tenant AND pdp_resource LIKE :prefix
            ORDER BY timestamp DESC
            LIMIT :max
            """, nativeQuery = true)
    List<Object[]> agentInboundActivity(@Param("tenant") String tenant, @Param("prefix") String prefix,
                                        @Param("max") int max);

    /**
     * Human-accountability source: every governed evaluation that carries the OBO delegation lineage
     * ({@code actChain}). The chain's first element is the verified root (a human, for OBO calls) and its
     * length is the delegation depth — the same fields the PDP already reads at {@code actChain[0]}. The acting
     * agent is {@code pdp_subject} (the chain's last hop). Row shape:
     * {@code [timestamp (Timestamp), pdp_subject, pdp_resource, pdp_action, pdp_decision, correlation_id, actChain (json text)]}.
     * The decision is carried on the paired {@code PDP_DECISION_RENDERED} row, so it is null here; accountability
     * does not need it. Newest first, bounded.
     */
    @Query(value = """
            SELECT timestamp, pdp_subject, pdp_resource, pdp_action, pdp_decision, correlation_id,
                   (pdp_context -> 'actChain')::text AS act_chain
            FROM pdp_audit_log
            WHERE event_type = 'PDP_EVALUATION_REQUESTED' AND ws_tenant_name = :tenant
              AND jsonb_exists(pdp_context, 'actChain')
            ORDER BY timestamp DESC
            LIMIT :max
            """, nativeQuery = true)
    List<Object[]> accountabilityEvaluations(@Param("tenant") String tenant, @Param("max") int max);

    /** Same as {@link #accountabilityEvaluations} but for a single acting agent ({@code pdp_subject}). */
    @Query(value = """
            SELECT timestamp, pdp_subject, pdp_resource, pdp_action, pdp_decision, correlation_id,
                   (pdp_context -> 'actChain')::text AS act_chain
            FROM pdp_audit_log
            WHERE event_type = 'PDP_EVALUATION_REQUESTED' AND ws_tenant_name = :tenant
              AND pdp_subject = :agent AND jsonb_exists(pdp_context, 'actChain')
            ORDER BY timestamp DESC
            LIMIT :max
            """, nativeQuery = true)
    List<Object[]> accountabilityEvaluationsForAgent(@Param("tenant") String tenant, @Param("agent") String agent,
                                                     @Param("max") int max);

    /**
     * Enforcement-health signal for the posture scorecard: decisions grouped by outcome and how they were reached.
     * Row shape: {@code [pdp_decision, decisionBasis (may be blank on legacy rows), count]}. A non-zero DENY count
     * (esp. DEFAULT_DENY / a matching forbid) is evidence the default-deny gate is actually enforcing.
     */
    @Query(value = """
            SELECT pdp_decision, COALESCE(pdp_context ->> 'decisionBasis', '') AS basis, COUNT(*)
            FROM pdp_audit_log
            WHERE event_type = 'PDP_DECISION_RENDERED' AND ws_tenant_name = :tenant
            GROUP BY pdp_decision, COALESCE(pdp_context ->> 'decisionBasis', '')
            """, nativeQuery = true)
    List<Object[]> decisionOutcomeCounts(@Param("tenant") String tenant);

    // ── Point-in-time (Stage 8): time-bounded reconstruction from the decision ledger [from, to) ──────────────

    /** The ledger's full timestamp span for a tenant — the rewindable coverage window. Row: {@code [min, max]}. */
    @Query(value = "SELECT MIN(timestamp), MAX(timestamp) FROM pdp_audit_log WHERE ws_tenant_name = :tenant",
            nativeQuery = true)
    List<Object[]> ledgerSpan(@Param("tenant") String tenant);

    /** Enforcement outcomes within the window. Row: {@code [pdp_decision, decisionBasis, count]}. */
    @Query(value = """
            SELECT pdp_decision, COALESCE(pdp_context ->> 'decisionBasis', '') AS basis, COUNT(*)
            FROM pdp_audit_log
            WHERE event_type = 'PDP_DECISION_RENDERED' AND ws_tenant_name = :tenant
              AND timestamp >= :from AND timestamp < :to
            GROUP BY pdp_decision, COALESCE(pdp_context ->> 'decisionBasis', '')
            """, nativeQuery = true)
    List<Object[]> windowEnforcement(@Param("tenant") String tenant,
                                     @Param("from") java.time.LocalDateTime from,
                                     @Param("to") java.time.LocalDateTime to);

    /** Per-agent activity within the window. Row: {@code [pdp_subject, total, allowed, denied, distinctResources]}. */
    @Query(value = """
            SELECT pdp_subject, COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE pdp_decision = 'ALLOW') AS allowed,
                   COUNT(*) FILTER (WHERE pdp_decision = 'DENY') AS denied,
                   COUNT(DISTINCT pdp_resource) AS distinct_resources
            FROM pdp_audit_log
            WHERE event_type = 'PDP_DECISION_RENDERED' AND ws_tenant_name = :tenant
              AND timestamp >= :from AND timestamp < :to
            GROUP BY pdp_subject
            ORDER BY total DESC
            """, nativeQuery = true)
    List<Object[]> windowAgentActivity(@Param("tenant") String tenant,
                                       @Param("from") java.time.LocalDateTime from,
                                       @Param("to") java.time.LocalDateTime to);

    /**
     * Policies that were actively deciding within the window (the effective set, by evidence). Row:
     * {@code [policyId, count]}. Deliberately does NOT restrict to current {@code cedar_policy_id}s (unlike
     * {@link #aggregatePolicyActivity}) — point-in-time must surface a policy that was deciding <em>then</em> even
     * if it has since been deleted (the caller flags it {@code stillPresent=false}). The {@code DEFAULT_DENY}
     * sentinel and blank split-tokens are excluded; genuine deleted policies are kept.
     */
    @Query(value = """
            SELECT trim(pid) AS policy_id, COUNT(*)
            FROM pdp_audit_log, unnest(string_to_array(pdp_policy_id, ',')) AS pid
            WHERE event_type = 'PDP_DECISION_RENDERED' AND ws_tenant_name = :tenant
              AND timestamp >= :from AND timestamp < :to
              AND pdp_policy_id IS NOT NULL AND pdp_policy_id <> ''
              AND trim(pid) <> 'DEFAULT_DENY' AND trim(pid) <> ''
            GROUP BY trim(pid)
            ORDER BY 2 DESC
            """, nativeQuery = true)
    List<Object[]> windowPolicyActivity(@Param("tenant") String tenant,
                                        @Param("from") java.time.LocalDateTime from,
                                        @Param("to") java.time.LocalDateTime to);

    /**
     * Accountability roll-up within the window, from the OBO actChain root. A request is accountable iff its root
     * is a verified human. Row: {@code [totalEvaluations, accountable, distinctHumans]} (unrooted = total − accountable).
     */
    @Query(value = """
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE COALESCE(pdp_context -> 'actChain' -> 0 ->> 'type', '') = 'human'
                                      AND (pdp_context -> 'actChain' -> 0 ->> 'verified') = 'true') AS accountable,
                   COUNT(DISTINCT pdp_context -> 'actChain' -> 0 ->> 'username')
                       FILTER (WHERE COALESCE(pdp_context -> 'actChain' -> 0 ->> 'type', '') = 'human'
                                 AND (pdp_context -> 'actChain' -> 0 ->> 'verified') = 'true') AS humans
            FROM pdp_audit_log
            WHERE event_type = 'PDP_EVALUATION_REQUESTED' AND ws_tenant_name = :tenant
              AND timestamp >= :from AND timestamp < :to
            """, nativeQuery = true)
    List<Object[]> windowAccountability(@Param("tenant") String tenant,
                                        @Param("from") java.time.LocalDateTime from,
                                        @Param("to") java.time.LocalDateTime to);

    /**
     * Forensic evidence drill-down: the individual authorization decisions in the window, newest first, optionally
     * filtered by acting agent and/or outcome. The on-behalf-of human is recovered by correlating each decision to
     * its evaluation's delegation chain (null when the caller was anonymous/system). Pass {@code ""} for a filter to
     * disable it. Row shape:
     * {@code [timestamp, agent, human, action, resource, decision, policy, correlation_id]}.
     */
    @Query(value = """
            SELECT r.timestamp, r.pdp_subject, ev.username, r.pdp_action, r.pdp_resource,
                   r.pdp_decision, r.pdp_policy_id, r.correlation_id
            FROM pdp_audit_log r
            LEFT JOIN (
                SELECT correlation_id, MIN(pdp_context -> 'actChain' -> 0 ->> 'username') AS username
                FROM pdp_audit_log
                WHERE event_type = 'PDP_EVALUATION_REQUESTED' AND ws_tenant_name = :tenant
                  AND timestamp >= :from AND timestamp < :to AND correlation_id IS NOT NULL AND correlation_id <> ''
                GROUP BY correlation_id
            ) ev ON ev.correlation_id = r.correlation_id
            WHERE r.event_type = 'PDP_DECISION_RENDERED' AND r.ws_tenant_name = :tenant
              AND r.timestamp >= :from AND r.timestamp < :to
              AND (:agent = '' OR r.pdp_subject = :agent)
              AND (:decision = '' OR r.pdp_decision = :decision)
            ORDER BY r.timestamp DESC
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> windowEvents(@Param("tenant") String tenant,
                                @Param("from") java.time.LocalDateTime from,
                                @Param("to") java.time.LocalDateTime to,
                                @Param("agent") String agent,
                                @Param("decision") String decision,
                                @Param("size") int size,
                                @Param("offset") int offset);

    /** Total count for {@link #windowEvents} pagination — same window + filters, without the human join. */
    @Query(value = """
            SELECT COUNT(*)
            FROM pdp_audit_log r
            WHERE r.event_type = 'PDP_DECISION_RENDERED' AND r.ws_tenant_name = :tenant
              AND r.timestamp >= :from AND r.timestamp < :to
              AND (:agent = '' OR r.pdp_subject = :agent)
              AND (:decision = '' OR r.pdp_decision = :decision)
            """, nativeQuery = true)
    long windowEventsCount(@Param("tenant") String tenant,
                           @Param("from") java.time.LocalDateTime from,
                           @Param("to") java.time.LocalDateTime to,
                           @Param("agent") String agent,
                           @Param("decision") String decision);
}
