package com.ws.wsAgenticSecurityGateway.audit.repository;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.GatewayAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface GatewayAuditLogRepository
        extends JpaRepository<GatewayAuditLog, UUID>, JpaSpecificationExecutor<GatewayAuditLog> {

    Page<GatewayAuditLog> findByModule(AuditModule module, Pageable pageable);

    Page<GatewayAuditLog> findByEventType(AuditEventType eventType, Pageable pageable);

    Page<GatewayAuditLog> findByStatus(AuditStatus status, Pageable pageable);

    Page<GatewayAuditLog> findByServerName(String serverName, Pageable pageable);

    List<GatewayAuditLog> findByCorrelationId(String correlationId);

    /**
     * Of the given correlation ids, the distinct ones that have an event of {@code eventType} — used to flag
     * which legs on a returned audit page have an OBO receipt (a {@code STS_TOKEN_MINTED} row), so the button
     * can show on every event of that leg. The id set is already tenant-scoped by the page it came from.
     */
    @Query("SELECT DISTINCT a.correlationId FROM GatewayAuditLog a "
            + "WHERE a.eventType = :eventType AND a.correlationId IN :correlationIds")
    List<String> findCorrelationIdsWithEventType(@Param("eventType") AuditEventType eventType,
                                                 @Param("correlationIds") Collection<String> correlationIds);

    List<GatewayAuditLog> findByTraceId(String traceId);

    List<GatewayAuditLog> findBySessionId(String sessionId);

    /**
     * Distinct trace ids for one agent (matched by OAuth client id OR policy principal name — covers both
     * session and stateless rows), most-recent first. One trace id == one "activity". Paginated because an
     * agent accrues unbounded requests over time.
     */
    @Query("SELECT m.traceId FROM GatewayAuditLog m WHERE m.traceId IS NOT NULL "
            + "AND (m.agentClientId = :key OR m.agentName = :key) "
            + "GROUP BY m.traceId ORDER BY MAX(m.timestamp) DESC")
    List<String> findDistinctTraceIdsByAgentKey(@Param("key") String key, Pageable pageable);

    /** All events for a set of traces, oldest first — the rows a set of activities roll up from. */
    List<GatewayAuditLog> findByTraceIdInOrderByTimestampAsc(List<String> traceIds);

    /**
     * Identity-graph edge aggregation: {@code human → agent} ("acts-through"). Counts DISTINCT requests
     * (traces), so multiple audit events per request don't inflate the edge. Returns
     * {@code [userIdentity, humanUserId, agentName, agentClientId, requestCount]}.
     */
    @Query("SELECT m.userIdentity, m.humanUserId, m.agentName, m.agentClientId, COUNT(DISTINCT m.traceId) "
            + "FROM GatewayAuditLog m "
            + "WHERE m.agentName IS NOT NULL AND m.userIdentity IS NOT NULL AND m.traceId IS NOT NULL "
            + "AND (CAST(:tenant AS string) IS NULL OR m.wsTenantName = :tenant) "
            + "AND m.timestamp >= :since "
            + "GROUP BY m.userIdentity, m.humanUserId, m.agentName, m.agentClientId")
    List<Object[]> aggregateHumanAgentEdges(@Param("tenant") String tenant,
                                            @Param("since") LocalDateTime since);

    /**
     * Identity-graph edge aggregation: {@code agent → tool} ("invoked"), split by decision so the edge can
     * show allow/deny. Sourced from PDP decision rows (one per tool call). Returns
     * {@code [agentName, agentClientId, capabilityName, serverName, status, requestCount]}.
     */
    @Query("SELECT m.agentName, m.agentClientId, m.capabilityName, m.serverName, m.status, "
            + "COUNT(DISTINCT m.traceId) "
            + "FROM GatewayAuditLog m "
            + "WHERE m.eventType = :eventType AND m.capabilityName IS NOT NULL AND m.agentName IS NOT NULL "
            + "AND (CAST(:tenant AS string) IS NULL OR m.wsTenantName = :tenant) "
            + "AND m.timestamp >= :since "
            + "GROUP BY m.agentName, m.agentClientId, m.capabilityName, m.serverName, m.status")
    List<Object[]> aggregateAgentToolEdges(@Param("eventType") AuditEventType eventType,
                                           @Param("tenant") String tenant,
                                           @Param("since") LocalDateTime since);

    /**
     * Agent OUTBOUND activity roll-up (the calls THIS agent made): one row per
     * {@code [protocolMethod, target(serverName), capability, decision(pdpDecision), calls, lastAt]}.
     * Sourced from PDP decision rows; matches the agent by its client id or (possibly version-suffixed) name.
     */
    @Query("SELECT m.protocolMethod, m.serverName, m.capabilityName, m.pdpDecision, COUNT(m), MAX(m.timestamp) "
            + "FROM GatewayAuditLog m "
            + "WHERE m.eventType = :eventType AND m.serverName IS NOT NULL "
            + "AND (m.agentClientId = :key OR m.agentName = :key OR m.agentName LIKE CONCAT(:key, ' %')) "
            + "GROUP BY m.protocolMethod, m.serverName, m.capabilityName, m.pdpDecision")
    List<Object[]> aggregateAgentOutbound(@Param("eventType") AuditEventType eventType, @Param("key") String key);

    /**
     * Agent INBOUND activity roll-up (the calls made TO this agent — its skills invoked): one row per
     * {@code [protocolMethod, caller(agentName), capability, decision(pdpDecision), calls, lastAt]}.
     */
    @Query("SELECT m.protocolMethod, m.agentName, m.capabilityName, m.pdpDecision, COUNT(m), MAX(m.timestamp) "
            + "FROM GatewayAuditLog m "
            + "WHERE m.eventType = :eventType AND m.serverName = :key "
            + "GROUP BY m.protocolMethod, m.agentName, m.capabilityName, m.pdpDecision")
    List<Object[]> aggregateAgentInbound(@Param("eventType") AuditEventType eventType, @Param("key") String key);

    Page<GatewayAuditLog> findByTimestampBetween(LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<GatewayAuditLog> findByModuleAndTimestampBetween(
            AuditModule module, LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<GatewayAuditLog> findByModuleAndStatus(AuditModule module, AuditStatus status, Pageable pageable);

    long countByModuleAndStatus(AuditModule module, AuditStatus status);

    long countByStatus(AuditStatus status);

    long countByModule(AuditModule module);

    long countBySeverity(AuditSeverity severity);

    @Query("SELECT AVG(m.durationMs) FROM GatewayAuditLog m WHERE m.durationMs IS NOT NULL")
    Double findAverageDurationMs();

    @Query("SELECT AVG(m.durationMs) FROM GatewayAuditLog m WHERE m.durationMs IS NOT NULL AND m.timestamp >= :since")
    Double findAverageDurationMsSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT date_trunc('hour', m.timestamp) AS bucket, COUNT(*) AS cnt " +
            "FROM ws_agentic_security.gateway_audit_log m " +
            "WHERE m.timestamp >= :since " +
            "GROUP BY date_trunc('hour', m.timestamp) ORDER BY bucket",
            nativeQuery = true)
    List<Object[]> countByHourSince(@Param("since") LocalDateTime since);

    @Query("SELECT DISTINCT m.serverName FROM GatewayAuditLog m WHERE m.serverName IS NOT NULL ORDER BY m.serverName")
    List<String> findDistinctServerNames();

    @Query("SELECT DISTINCT m.capabilityName FROM GatewayAuditLog m WHERE m.capabilityName IS NOT NULL ORDER BY m.capabilityName")
    List<String> findDistinctCapabilityNames();

    @Query("SELECT DISTINCT m.agentName FROM GatewayAuditLog m WHERE m.agentName IS NOT NULL ORDER BY m.agentName")
    List<String> findDistinctAgentNames();

    @Query("SELECT DISTINCT m.userIdentity FROM GatewayAuditLog m WHERE m.userIdentity IS NOT NULL ORDER BY m.userIdentity")
    List<String> findDistinctUserIdentities();

    long countByTimestampAfter(LocalDateTime since);

    long countByModuleAndTimestampAfter(AuditModule module, LocalDateTime since);

    long countByStatusAndTimestampAfter(AuditStatus status, LocalDateTime since);

    @Query(value = "SELECT " +
            "percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) AS p50, " +
            "percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95, " +
            "percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms) AS p99 " +
            "FROM ws_agentic_security.gateway_audit_log " +
            "WHERE duration_ms IS NOT NULL AND timestamp >= :since",
            nativeQuery = true)
    List<Object[]> findLatencyPercentilesSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT server_name, MAX(timestamp) AS last_activity " +
            "FROM ws_agentic_security.gateway_audit_log " +
            "WHERE server_name IS NOT NULL " +
            "GROUP BY server_name",
            nativeQuery = true)
    List<Object[]> findLastActivityPerServer();

    List<GatewayAuditLog> findTop5BySessionIdAndEventTypeOrderByTimestampDesc(
            String sessionId, AuditEventType eventType);

    @Query("SELECT COUNT(a) FROM GatewayAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND a.module = com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule.ORCHESTRATION_LAYER")
    long countBySessionIdsAndTimestampAfter(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query("SELECT a.status, COUNT(a) FROM GatewayAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND a.module = com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule.ORCHESTRATION_LAYER " +
            "GROUP BY a.status")
    List<Object[]> countByStatusGroupedForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query(value = "SELECT date_trunc('hour', timestamp) AS bucket, COUNT(*) AS cnt " +
            "FROM ws_agentic_security.gateway_audit_log " +
            "WHERE session_id IN :sessionIds AND timestamp > :since " +
            "AND module = 'ORCHESTRATION_LAYER' " +
            "GROUP BY bucket ORDER BY bucket",
            nativeQuery = true)
    List<Object[]> countByHourForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query("SELECT a.capabilityName, a.serverName, COUNT(a), AVG(a.durationMs) " +
            "FROM GatewayAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND a.eventType = com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType.ORCHESTRATION_RESPONSE_RETURNED " +
            "GROUP BY a.capabilityName, a.serverName ORDER BY COUNT(a) DESC")
    List<Object[]> toolUsageForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query("SELECT a.serverName, COUNT(a), " +
            "SUM(CASE WHEN a.status = com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus.ERROR " +
            "OR a.status = com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus.FAILURE THEN 1L ELSE 0L END) " +
            "FROM GatewayAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND a.module = com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule.ORCHESTRATION_LAYER " +
            "GROUP BY a.serverName")
    List<Object[]> serverUsageForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query(value = "SELECT " +
            "percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) AS p50, " +
            "percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95, " +
            "percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms) AS p99 " +
            "FROM ws_agentic_security.gateway_audit_log " +
            "WHERE session_id IN :sessionIds AND timestamp > :since " +
            "AND module = 'ORCHESTRATION_LAYER' AND duration_ms IS NOT NULL",
            nativeQuery = true)
    List<Object[]> latencyPercentilesForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query("SELECT a.errorCode, a.errorMessage, COUNT(a) FROM GatewayAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND (a.status = com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus.ERROR " +
            "OR a.status = com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus.FAILURE) " +
            "GROUP BY a.errorCode, a.errorMessage ORDER BY COUNT(a) DESC")
    List<Object[]> errorBreakdownForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    Page<GatewayAuditLog> findBySessionIdOrderByTimestampDesc(
            String sessionId, Pageable pageable);

    Page<GatewayAuditLog> findByModuleAndWsTenantName(AuditModule module, String wsTenantName, Pageable pageable);

    Page<GatewayAuditLog> findByEventTypeAndWsTenantName(AuditEventType eventType, String wsTenantName, Pageable pageable);

    Page<GatewayAuditLog> findByStatusAndWsTenantName(AuditStatus status, String wsTenantName, Pageable pageable);

    Page<GatewayAuditLog> findByServerNameAndWsTenantName(String serverName, String wsTenantName, Pageable pageable);

    List<GatewayAuditLog> findByCorrelationIdAndWsTenantName(String correlationId, String wsTenantName);

    List<GatewayAuditLog> findBySessionIdAndWsTenantName(String sessionId, String wsTenantName);

    Page<GatewayAuditLog> findByTimestampBetweenAndWsTenantName(LocalDateTime from, LocalDateTime to, String wsTenantName, Pageable pageable);

    Page<GatewayAuditLog> findByModuleAndTimestampBetweenAndWsTenantName(
            AuditModule module, LocalDateTime from, LocalDateTime to, String wsTenantName, Pageable pageable);

    Page<GatewayAuditLog> findByModuleAndStatusAndWsTenantName(AuditModule module, AuditStatus status, String wsTenantName, Pageable pageable);

    long countByModuleAndStatusAndWsTenantName(AuditModule module, AuditStatus status, String wsTenantName);

    long countByStatusAndWsTenantName(AuditStatus status, String wsTenantName);

    long countByModuleAndWsTenantName(AuditModule module, String wsTenantName);

    long countBySeverityAndWsTenantName(AuditSeverity severity, String wsTenantName);

    @Query("SELECT AVG(m.durationMs) FROM GatewayAuditLog m WHERE m.durationMs IS NOT NULL AND m.wsTenantName = :wsTenantName")
    Double findAverageDurationMsByTenant(@Param("wsTenantName") String wsTenantName);

    @Query("SELECT AVG(m.durationMs) FROM GatewayAuditLog m WHERE m.durationMs IS NOT NULL AND m.timestamp >= :since AND m.wsTenantName = :wsTenantName")
    Double findAverageDurationMsSinceByTenant(@Param("since") LocalDateTime since, @Param("wsTenantName") String wsTenantName);

    @Query(value = "SELECT date_trunc('hour', m.timestamp) AS bucket, COUNT(*) AS cnt " +
            "FROM ws_agentic_security.gateway_audit_log m " +
            "WHERE m.timestamp >= :since AND m.ws_tenant_name = :wsTenantName " +
            "GROUP BY date_trunc('hour', m.timestamp) ORDER BY bucket",
            nativeQuery = true)
    List<Object[]> countByHourSinceByTenant(@Param("since") LocalDateTime since, @Param("wsTenantName") String wsTenantName);

    @Query("SELECT DISTINCT m.serverName FROM GatewayAuditLog m WHERE m.serverName IS NOT NULL AND m.wsTenantName = :wsTenantName ORDER BY m.serverName")
    List<String> findDistinctServerNamesByTenant(@Param("wsTenantName") String wsTenantName);

    @Query("SELECT DISTINCT m.capabilityName FROM GatewayAuditLog m WHERE m.capabilityName IS NOT NULL AND m.wsTenantName = :wsTenantName ORDER BY m.capabilityName")
    List<String> findDistinctCapabilityNamesByTenant(@Param("wsTenantName") String wsTenantName);

    @Query("SELECT DISTINCT m.agentName FROM GatewayAuditLog m WHERE m.agentName IS NOT NULL AND m.wsTenantName = :wsTenantName ORDER BY m.agentName")
    List<String> findDistinctAgentNamesByTenant(@Param("wsTenantName") String wsTenantName);

    @Query("SELECT DISTINCT m.userIdentity FROM GatewayAuditLog m WHERE m.userIdentity IS NOT NULL AND m.wsTenantName = :wsTenantName ORDER BY m.userIdentity")
    List<String> findDistinctUserIdentitiesByTenant(@Param("wsTenantName") String wsTenantName);

    long countByTimestampAfterAndWsTenantName(LocalDateTime since, String wsTenantName);

    long countByModuleAndTimestampAfterAndWsTenantName(AuditModule module, LocalDateTime since, String wsTenantName);

    long countByStatusAndTimestampAfterAndWsTenantName(AuditStatus status, LocalDateTime since, String wsTenantName);

    @Query(value = "SELECT " +
            "percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) AS p50, " +
            "percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95, " +
            "percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms) AS p99 " +
            "FROM ws_agentic_security.gateway_audit_log " +
            "WHERE duration_ms IS NOT NULL AND timestamp >= :since AND ws_tenant_name = :wsTenantName",
            nativeQuery = true)
    List<Object[]> findLatencyPercentilesSinceByTenant(@Param("since") LocalDateTime since, @Param("wsTenantName") String wsTenantName);

    @Query(value = "SELECT server_name, MAX(timestamp) AS last_activity " +
            "FROM ws_agentic_security.gateway_audit_log " +
            "WHERE server_name IS NOT NULL AND ws_tenant_name = :wsTenantName " +
            "GROUP BY server_name",
            nativeQuery = true)
    List<Object[]> findLastActivityPerServerByTenant(@Param("wsTenantName") String wsTenantName);

    List<GatewayAuditLog> findTop5BySessionIdAndEventTypeAndWsTenantNameOrderByTimestampDesc(
            String sessionId, AuditEventType eventType, String wsTenantName);

    Page<GatewayAuditLog> findBySessionIdAndWsTenantNameOrderByTimestampDesc(
            String sessionId, String wsTenantName, Pageable pageable);

    /**
     * Tenant-level audit coverage for the SOC 2 evidence pack (CC7.2 logging/monitoring). Single row:
     * {@code [total_events (Long), event_types (Long), human_attributed (Long), distinct_humans (Long),
     * distinct_agents (Long), first_event (Timestamp), last_event (Timestamp)]}.
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

    /** Distinct verified humans an agent has acted on behalf of — for the Agent Activity Trail summary (CC-style accountability). */
    @Query(value = """
            SELECT COUNT(DISTINCT human_user_id)
            FROM gateway_audit_log
            WHERE ws_tenant_name = :tenant AND agent_name = :agent AND human_user_id IS NOT NULL
            """, nativeQuery = true)
    long countDistinctHumansForAgent(@Param("tenant") String tenant, @Param("agent") String agent);
}
