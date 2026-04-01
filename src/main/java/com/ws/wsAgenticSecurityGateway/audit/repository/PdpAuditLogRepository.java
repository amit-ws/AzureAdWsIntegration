package com.ws.wsAgenticSecurityGateway.audit.repository;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.PdpAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
