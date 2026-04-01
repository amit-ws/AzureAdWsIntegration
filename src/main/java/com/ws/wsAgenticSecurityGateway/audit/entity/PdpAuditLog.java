package com.ws.wsAgenticSecurityGateway.audit.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.converter.JsonNodeColumnConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "pdp_audit_log", schema = "ws_agentic_security",
        indexes = {
                @Index(name = "idx_pdp_correlation_id", columnList = "correlation_id"),
                @Index(name = "idx_pdp_event_type", columnList = "event_type"),
                @Index(name = "idx_pdp_subject", columnList = "pdp_subject"),
                @Index(name = "idx_pdp_resource", columnList = "pdp_resource"),
                @Index(name = "idx_pdp_decision", columnList = "pdp_decision"),
                @Index(name = "idx_pdp_timestamp", columnList = "timestamp"),
                @Index(name = "idx_pdp_status", columnList = "status")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PdpAuditLog {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 60)
    private AuditEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuditStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    @Builder.Default
    private AuditSeverity severity = AuditSeverity.INFO;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "pdp_subject", nullable = false, length = 256)
    private String pdpSubject;

    @Column(name = "pdp_resource", nullable = false, length = 256)
    private String pdpResource;

    @Column(name = "pdp_action", nullable = false, length = 128)
    private String pdpAction;

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "pdp_context", columnDefinition = "JSONB")
    private JsonNode pdpContext;

    @Column(name = "pdp_decision", length = 20)
    private String pdpDecision;

    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    @CreationTimestamp
    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @Column(name = "schema_version", nullable = false, length = 10)
    @Builder.Default
    private String schemaVersion = "1.0";
}
