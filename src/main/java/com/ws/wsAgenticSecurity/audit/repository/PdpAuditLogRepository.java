package com.ws.wsAgenticSecurity.audit.repository;

import com.ws.wsAgenticSecurity.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurity.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurity.audit.entity.PdpAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence gateway for {@link PdpAuditLog} records.
 */
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
}
