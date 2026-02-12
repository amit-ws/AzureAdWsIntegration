package com.ws.wsAgenticSecurityGateway.audit.repository;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence gateway for {@link McpAuditLog} records.
 */
@Repository
public interface McpAuditLogRepository extends JpaRepository<McpAuditLog, UUID> {

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
}
