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

    long countByTimestampAfter(LocalDateTime since);

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
}
