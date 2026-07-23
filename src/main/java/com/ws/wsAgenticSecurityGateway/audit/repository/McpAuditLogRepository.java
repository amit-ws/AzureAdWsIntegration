package com.ws.wsAgenticSecurityGateway.audit.repository;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface McpAuditLogRepository
        extends JpaRepository<McpAuditLog, UUID>, JpaSpecificationExecutor<McpAuditLog> {

    Page<McpAuditLog> findByModule(AuditModule module, Pageable pageable);

    Page<McpAuditLog> findByEventType(AuditEventType eventType, Pageable pageable);

    Page<McpAuditLog> findByStatus(AuditStatus status, Pageable pageable);

    Page<McpAuditLog> findByServerName(String serverName, Pageable pageable);

    List<McpAuditLog> findByCorrelationId(String correlationId);

    List<McpAuditLog> findByTraceId(String traceId);

    List<McpAuditLog> findBySessionId(String sessionId);

    /**
     * Distinct trace ids for one agent (matched by OAuth client id OR policy principal name — covers both
     * session and stateless rows), most-recent first. One trace id == one "activity". Paginated because an
     * agent accrues unbounded requests over time.
     */
    @Query("SELECT m.traceId FROM McpAuditLog m WHERE m.traceId IS NOT NULL "
            + "AND (m.agentClientId = :key OR m.agentName = :key) "
            + "GROUP BY m.traceId ORDER BY MAX(m.timestamp) DESC")
    List<String> findDistinctTraceIdsByAgentKey(@Param("key") String key, Pageable pageable);

    /** All events for a set of traces, oldest first — the rows a set of activities roll up from. */
    List<McpAuditLog> findByTraceIdInOrderByTimestampAsc(List<String> traceIds);

    /**
     * Identity-graph edge aggregation: {@code human → agent} ("acts-through"). Counts DISTINCT requests
     * (traces), so multiple audit events per request don't inflate the edge. Returns
     * {@code [userIdentity, humanUserId, agentName, agentClientId, requestCount]}.
     */
    @Query("SELECT m.userIdentity, m.humanUserId, m.agentName, m.agentClientId, COUNT(DISTINCT m.traceId) "
            + "FROM McpAuditLog m "
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
            + "FROM McpAuditLog m "
            + "WHERE m.eventType = :eventType AND m.capabilityName IS NOT NULL AND m.agentName IS NOT NULL "
            + "AND (CAST(:tenant AS string) IS NULL OR m.wsTenantName = :tenant) "
            + "AND m.timestamp >= :since "
            + "GROUP BY m.agentName, m.agentClientId, m.capabilityName, m.serverName, m.status")
    List<Object[]> aggregateAgentToolEdges(@Param("eventType") AuditEventType eventType,
                                           @Param("tenant") String tenant,
                                           @Param("since") LocalDateTime since);

    Page<McpAuditLog> findByTimestampBetween(LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<McpAuditLog> findByModuleAndTimestampBetween(
            AuditModule module, LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<McpAuditLog> findByModuleAndStatus(AuditModule module, AuditStatus status, Pageable pageable);

    long countByModuleAndStatus(AuditModule module, AuditStatus status);

    long countByStatus(AuditStatus status);

    long countByModule(AuditModule module);

    long countBySeverity(AuditSeverity severity);

    @Query("SELECT AVG(m.durationMs) FROM McpAuditLog m WHERE m.durationMs IS NOT NULL")
    Double findAverageDurationMs();

    @Query("SELECT AVG(m.durationMs) FROM McpAuditLog m WHERE m.durationMs IS NOT NULL AND m.timestamp >= :since")
    Double findAverageDurationMsSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT date_trunc('hour', m.timestamp) AS bucket, COUNT(*) AS cnt " +
            "FROM ws_agentic_security.mcp_audit_log m " +
            "WHERE m.timestamp >= :since " +
            "GROUP BY date_trunc('hour', m.timestamp) ORDER BY bucket",
            nativeQuery = true)
    List<Object[]> countByHourSince(@Param("since") LocalDateTime since);

    @Query("SELECT DISTINCT m.serverName FROM McpAuditLog m WHERE m.serverName IS NOT NULL ORDER BY m.serverName")
    List<String> findDistinctServerNames();

    @Query("SELECT DISTINCT m.capabilityName FROM McpAuditLog m WHERE m.capabilityName IS NOT NULL ORDER BY m.capabilityName")
    List<String> findDistinctCapabilityNames();

    @Query("SELECT DISTINCT m.agentName FROM McpAuditLog m WHERE m.agentName IS NOT NULL ORDER BY m.agentName")
    List<String> findDistinctAgentNames();

    @Query("SELECT DISTINCT m.userIdentity FROM McpAuditLog m WHERE m.userIdentity IS NOT NULL ORDER BY m.userIdentity")
    List<String> findDistinctUserIdentities();

    long countByTimestampAfter(LocalDateTime since);

    long countByModuleAndTimestampAfter(AuditModule module, LocalDateTime since);

    long countByStatusAndTimestampAfter(AuditStatus status, LocalDateTime since);

    @Query(value = "SELECT " +
            "percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) AS p50, " +
            "percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95, " +
            "percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms) AS p99 " +
            "FROM ws_agentic_security.mcp_audit_log " +
            "WHERE duration_ms IS NOT NULL AND timestamp >= :since",
            nativeQuery = true)
    List<Object[]> findLatencyPercentilesSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT server_name, MAX(timestamp) AS last_activity " +
            "FROM ws_agentic_security.mcp_audit_log " +
            "WHERE server_name IS NOT NULL " +
            "GROUP BY server_name",
            nativeQuery = true)
    List<Object[]> findLastActivityPerServer();

    List<McpAuditLog> findTop5BySessionIdAndEventTypeOrderByTimestampDesc(
            String sessionId, AuditEventType eventType);

    @Query("SELECT COUNT(a) FROM McpAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND a.module = com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule.ORCHESTRATION_LAYER")
    long countBySessionIdsAndTimestampAfter(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query("SELECT a.status, COUNT(a) FROM McpAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND a.module = com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule.ORCHESTRATION_LAYER " +
            "GROUP BY a.status")
    List<Object[]> countByStatusGroupedForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query(value = "SELECT date_trunc('hour', timestamp) AS bucket, COUNT(*) AS cnt " +
            "FROM ws_agentic_security.mcp_audit_log " +
            "WHERE session_id IN :sessionIds AND timestamp > :since " +
            "AND module = 'ORCHESTRATION_LAYER' " +
            "GROUP BY bucket ORDER BY bucket",
            nativeQuery = true)
    List<Object[]> countByHourForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query("SELECT a.capabilityName, a.serverName, COUNT(a), AVG(a.durationMs) " +
            "FROM McpAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND a.eventType = com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType.ORCHESTRATION_RESPONSE_RETURNED " +
            "GROUP BY a.capabilityName, a.serverName ORDER BY COUNT(a) DESC")
    List<Object[]> toolUsageForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query("SELECT a.serverName, COUNT(a), " +
            "SUM(CASE WHEN a.status = com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus.ERROR " +
            "OR a.status = com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus.FAILURE THEN 1L ELSE 0L END) " +
            "FROM McpAuditLog a " +
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
            "FROM ws_agentic_security.mcp_audit_log " +
            "WHERE session_id IN :sessionIds AND timestamp > :since " +
            "AND module = 'ORCHESTRATION_LAYER' AND duration_ms IS NOT NULL",
            nativeQuery = true)
    List<Object[]> latencyPercentilesForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    @Query("SELECT a.errorCode, a.errorMessage, COUNT(a) FROM McpAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND (a.status = com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus.ERROR " +
            "OR a.status = com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus.FAILURE) " +
            "GROUP BY a.errorCode, a.errorMessage ORDER BY COUNT(a) DESC")
    List<Object[]> errorBreakdownForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    Page<McpAuditLog> findBySessionIdOrderByTimestampDesc(
            String sessionId, Pageable pageable);

    Page<McpAuditLog> findByModuleAndWsTenantName(AuditModule module, String wsTenantName, Pageable pageable);

    Page<McpAuditLog> findByEventTypeAndWsTenantName(AuditEventType eventType, String wsTenantName, Pageable pageable);

    Page<McpAuditLog> findByStatusAndWsTenantName(AuditStatus status, String wsTenantName, Pageable pageable);

    Page<McpAuditLog> findByServerNameAndWsTenantName(String serverName, String wsTenantName, Pageable pageable);

    List<McpAuditLog> findByCorrelationIdAndWsTenantName(String correlationId, String wsTenantName);

    List<McpAuditLog> findBySessionIdAndWsTenantName(String sessionId, String wsTenantName);

    Page<McpAuditLog> findByTimestampBetweenAndWsTenantName(LocalDateTime from, LocalDateTime to, String wsTenantName, Pageable pageable);

    Page<McpAuditLog> findByModuleAndTimestampBetweenAndWsTenantName(
            AuditModule module, LocalDateTime from, LocalDateTime to, String wsTenantName, Pageable pageable);

    Page<McpAuditLog> findByModuleAndStatusAndWsTenantName(AuditModule module, AuditStatus status, String wsTenantName, Pageable pageable);

    long countByModuleAndStatusAndWsTenantName(AuditModule module, AuditStatus status, String wsTenantName);

    long countByStatusAndWsTenantName(AuditStatus status, String wsTenantName);

    long countByModuleAndWsTenantName(AuditModule module, String wsTenantName);

    long countBySeverityAndWsTenantName(AuditSeverity severity, String wsTenantName);

    @Query("SELECT AVG(m.durationMs) FROM McpAuditLog m WHERE m.durationMs IS NOT NULL AND m.wsTenantName = :wsTenantName")
    Double findAverageDurationMsByTenant(@Param("wsTenantName") String wsTenantName);

    @Query("SELECT AVG(m.durationMs) FROM McpAuditLog m WHERE m.durationMs IS NOT NULL AND m.timestamp >= :since AND m.wsTenantName = :wsTenantName")
    Double findAverageDurationMsSinceByTenant(@Param("since") LocalDateTime since, @Param("wsTenantName") String wsTenantName);

    @Query(value = "SELECT date_trunc('hour', m.timestamp) AS bucket, COUNT(*) AS cnt " +
            "FROM ws_agentic_security.mcp_audit_log m " +
            "WHERE m.timestamp >= :since AND m.ws_tenant_name = :wsTenantName " +
            "GROUP BY date_trunc('hour', m.timestamp) ORDER BY bucket",
            nativeQuery = true)
    List<Object[]> countByHourSinceByTenant(@Param("since") LocalDateTime since, @Param("wsTenantName") String wsTenantName);

    @Query("SELECT DISTINCT m.serverName FROM McpAuditLog m WHERE m.serverName IS NOT NULL AND m.wsTenantName = :wsTenantName ORDER BY m.serverName")
    List<String> findDistinctServerNamesByTenant(@Param("wsTenantName") String wsTenantName);

    @Query("SELECT DISTINCT m.capabilityName FROM McpAuditLog m WHERE m.capabilityName IS NOT NULL AND m.wsTenantName = :wsTenantName ORDER BY m.capabilityName")
    List<String> findDistinctCapabilityNamesByTenant(@Param("wsTenantName") String wsTenantName);

    @Query("SELECT DISTINCT m.agentName FROM McpAuditLog m WHERE m.agentName IS NOT NULL AND m.wsTenantName = :wsTenantName ORDER BY m.agentName")
    List<String> findDistinctAgentNamesByTenant(@Param("wsTenantName") String wsTenantName);

    @Query("SELECT DISTINCT m.userIdentity FROM McpAuditLog m WHERE m.userIdentity IS NOT NULL AND m.wsTenantName = :wsTenantName ORDER BY m.userIdentity")
    List<String> findDistinctUserIdentitiesByTenant(@Param("wsTenantName") String wsTenantName);

    long countByTimestampAfterAndWsTenantName(LocalDateTime since, String wsTenantName);

    long countByModuleAndTimestampAfterAndWsTenantName(AuditModule module, LocalDateTime since, String wsTenantName);

    long countByStatusAndTimestampAfterAndWsTenantName(AuditStatus status, LocalDateTime since, String wsTenantName);

    @Query(value = "SELECT " +
            "percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) AS p50, " +
            "percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95, " +
            "percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms) AS p99 " +
            "FROM ws_agentic_security.mcp_audit_log " +
            "WHERE duration_ms IS NOT NULL AND timestamp >= :since AND ws_tenant_name = :wsTenantName",
            nativeQuery = true)
    List<Object[]> findLatencyPercentilesSinceByTenant(@Param("since") LocalDateTime since, @Param("wsTenantName") String wsTenantName);

    @Query(value = "SELECT server_name, MAX(timestamp) AS last_activity " +
            "FROM ws_agentic_security.mcp_audit_log " +
            "WHERE server_name IS NOT NULL AND ws_tenant_name = :wsTenantName " +
            "GROUP BY server_name",
            nativeQuery = true)
    List<Object[]> findLastActivityPerServerByTenant(@Param("wsTenantName") String wsTenantName);

    List<McpAuditLog> findTop5BySessionIdAndEventTypeAndWsTenantNameOrderByTimestampDesc(
            String sessionId, AuditEventType eventType, String wsTenantName);

    Page<McpAuditLog> findBySessionIdAndWsTenantNameOrderByTimestampDesc(
            String sessionId, String wsTenantName, Pageable pageable);
}
