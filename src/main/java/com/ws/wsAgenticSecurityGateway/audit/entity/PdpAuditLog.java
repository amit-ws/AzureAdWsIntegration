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

/**
 * Dedicated audit record for PDP (Policy Decision Point) evaluations.
 *
 * <p>Separated from {@link McpAuditLog} because:
 * <ul>
 *   <li>Every column is populated on every row — no sparse NULLs.</li>
 *   <li>Independent retention policies (compliance / SOC2 may differ).</li>
 *   <li>Independent indexing and partitioning strategies.</li>
 *   <li>Clean domain separation — PDP is a distinct concern.</li>
 * </ul>
 *
 * <p>Linked to {@code mcp_audit_log} via {@code correlation_id} (logical join, not FK).
 */
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

    // ── Event Identity ────────────────────────────────────────────────

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

    // ── Correlation ───────────────────────────────────────────────────

    /**
     * Links this PDP record to the originating request chain in {@code mcp_audit_log}.
     * This is the logical join key — NOT a foreign key.
     */
    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    // ── PDP Fields (all populated on every row) ───────────────────────

    /** The agent or user identity requesting access. */
    @Column(name = "pdp_subject", nullable = false, length = 256)
    private String pdpSubject;

    /** The capability / resource being accessed. */
    @Column(name = "pdp_resource", nullable = false, length = 256)
    private String pdpResource;

    /** The action being performed (e.g. "tools/call", "resources/read"). */
    @Column(name = "pdp_action", nullable = false, length = 128)
    private String pdpAction;

    /** Full environment / context sent to the PDP engine (no truncation). */
    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "pdp_context", columnDefinition = "JSONB")
    private JsonNode pdpContext;

    /** The decision rendered: ALLOW, DENY, etc. */
    @Column(name = "pdp_decision", length = 20)
    private String pdpDecision;

    // ── Error Context ────────────────────────────────────────────────

    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ── Timing & Metadata ─────────────────────────────────────────────

    @Column(name = "duration_ms")
    private Long durationMs;

    @CreationTimestamp
    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @Column(name = "schema_version", nullable = false, length = 10)
    @Builder.Default
    private String schemaVersion = "1.0";
}
