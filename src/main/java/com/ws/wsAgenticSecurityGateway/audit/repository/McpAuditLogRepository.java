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

/**
 * Persistence gateway for {@link McpAuditLog} records.
 *
 * <p>Extends {@link JpaSpecificationExecutor} for dynamic multi-field
 * filtering used by the admin dashboard audit log page.
 */
@Repository
public interface McpAuditLogRepository
        extends JpaRepository<McpAuditLog, UUID>, JpaSpecificationExecutor<McpAuditLog> {

    Page<McpAuditLog> findByModule(AuditModule module, Pageable pageable);

    Page<McpAuditLog> findByEventType(AuditEventType eventType, Pageable pageable);

    Page<McpAuditLog> findByStatus(AuditStatus status, Pageable pageable);

    Page<McpAuditLog> findByServerName(String serverName, Pageable pageable);

    List<McpAuditLog> findByCorrelationId(String correlationId);

    List<McpAuditLog> findBySessionId(String sessionId);

    Page<McpAuditLog> findByTimestampBetween(LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<McpAuditLog> findByModuleAndTimestampBetween(
            AuditModule module, LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<McpAuditLog> findByModuleAndStatus(AuditModule module, AuditStatus status, Pageable pageable);

    long countByModuleAndStatus(AuditModule module, AuditStatus status);

    // ── Dashboard stat queries ─────────────────────────────────────────

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

    // ── Health dashboard queries ────────────────────────────────────────

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

    // ── Live Session Monitor queries ─────────────────────────────────

    /** Recent audit events for a specific session (for recent-tools enrichment). */
    List<McpAuditLog> findTop5BySessionIdAndEventTypeOrderByTimestampDesc(
            String sessionId, AuditEventType eventType);

    // ── Per-Agent Usage Analytics queries ────────────────────────────

    /** Total orchestration events for a set of sessions in a time window. */
    @Query("SELECT COUNT(a) FROM McpAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND a.module = com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule.ORCHESTRATION_LAYER")
    long countBySessionIdsAndTimestampAfter(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    /** Status breakdown for a set of sessions. */
    @Query("SELECT a.status, COUNT(a) FROM McpAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND a.module = com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule.ORCHESTRATION_LAYER " +
            "GROUP BY a.status")
    List<Object[]> countByStatusGroupedForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    /** Hourly bucketed request counts for a set of sessions. */
    @Query(value = "SELECT date_trunc('hour', timestamp) AS bucket, COUNT(*) AS cnt " +
            "FROM ws_agentic_security.mcp_audit_log " +
            "WHERE session_id IN :sessionIds AND timestamp > :since " +
            "AND module = 'ORCHESTRATION_LAYER' " +
            "GROUP BY bucket ORDER BY bucket",
            nativeQuery = true)
    List<Object[]> countByHourForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    /** Tool usage breakdown: which tools each agent uses most. */
    @Query("SELECT a.capabilityName, a.serverName, COUNT(a), AVG(a.durationMs) " +
            "FROM McpAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND a.eventType = com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType.ORCHESTRATION_RESPONSE_RETURNED " +
            "GROUP BY a.capabilityName, a.serverName ORDER BY COUNT(a) DESC")
    List<Object[]> toolUsageForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    /** Server usage breakdown with error counts. */
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

    /** Latency percentiles (p50, p95, p99) for a set of sessions. */
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

    /** Error breakdown with error codes and messages. */
    @Query("SELECT a.errorCode, a.errorMessage, COUNT(a) FROM McpAuditLog a " +
            "WHERE a.sessionId IN :sessionIds AND a.timestamp > :since " +
            "AND (a.status = com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus.ERROR " +
            "OR a.status = com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus.FAILURE) " +
            "GROUP BY a.errorCode, a.errorMessage ORDER BY COUNT(a) DESC")
    List<Object[]> errorBreakdownForSessions(
            @Param("sessionIds") List<String> sessionIds,
            @Param("since") LocalDateTime since);

    // ── Session Timeline query ──────────────────────────────────────

    /** Paginated audit events for a specific session, newest first. */
    Page<McpAuditLog> findBySessionIdOrderByTimestampDesc(
            String sessionId, Pageable pageable);
}
