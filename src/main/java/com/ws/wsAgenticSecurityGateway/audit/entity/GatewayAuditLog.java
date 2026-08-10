package com.ws.wsAgenticSecurityGateway.audit.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.converter.JsonNodeColumnConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "gateway_audit_log", schema = "ws_agentic_security",
        indexes = {
                @Index(name = "idx_audit_event_type", columnList = "event_type"),
                @Index(name = "idx_audit_module", columnList = "module"),
                @Index(name = "idx_audit_status", columnList = "status"),
                @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
                @Index(name = "idx_audit_correlation_id", columnList = "correlation_id"),
                @Index(name = "idx_audit_trace_id", columnList = "trace_id"),
                @Index(name = "idx_audit_server_name", columnList = "server_name"),
                @Index(name = "idx_audit_session_id", columnList = "session_id"),
                @Index(name = "idx_audit_request_id", columnList = "request_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayAuditLog {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    /** Which protocol adapter produced this event — {@code MCP} today, {@code A2A} once that adapter lands. */
    @Column(name = "protocol", nullable = false, length = 20)
    @Builder.Default
    private String protocol = "MCP";

    /**
     * View-only flag (NOT persisted): true when this row's leg ({@code correlationId}) minted an OBO token, so
     * the dashboard can surface the "OBO Receipt" button on every event of a leg that has a receipt — not just
     * the mint row. Populated by the query service for the returned page.
     */
    @Transient
    private boolean hasOboReceipt;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 60)
    private AuditEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "module", nullable = false, length = 30)
    private AuditModule module;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuditStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    @Builder.Default
    private AuditSeverity severity = AuditSeverity.INFO;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Request-scoped umbrella id — one trace spans all legs/events of a request (single- or multi-hop). */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "event_sequence")
    private Integer eventSequence;

    @Column(name = "agent_name", length = 256)
    private String agentName;

    @Column(name = "auth_method", length = 32)
    private String authMethod;

    @Column(name = "auth_identity", length = 256)
    private String authIdentity;

    @Column(name = "user_identity", length = 256)
    private String userIdentity;

    @Column(name = "token_type", length = 32)
    private String tokenType;

    @Column(name = "agent_client_id", length = 256)
    private String agentClientId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_roles", columnDefinition = "jsonb")
    private List<String> agentRoles;

    @Column(name = "human_user_id", length = 64)
    private String humanUserId;

    @Column(name = "nhi_id", length = 64)
    private String nhiId;

    @Column(name = "source_ip", length = 64)
    private String sourceIp;

    @Column(name = "pdp_decision", length = 16)
    private String pdpDecision;

    /**
     * Display-only carrier for a PDP hop's reason — <b>not a persisted column</b>. The authoritative store is
     * {@code pdp_audit_log.pdp_reason}; the chain view merges it onto the timeline marker at read time. Always
     * null on rows read from the {@code gateway_audit_log} table itself. (Paired with {@link #pdpPolicyId}.)
     */
    @Transient
    private String pdpReason;

    /**
     * Display-only carrier for the deciding policy of a PDP hop — <b>not a persisted column</b>. The authoritative
     * store is {@code pdp_audit_log.pdp_policy_id}; the View-Trace/chain view merges it onto the timeline marker at
     * read time so it can show "decided by &lt;policy&gt;". Always null on rows read from the {@code gateway_audit_log}
     * table itself.
     */
    @Transient
    private String pdpPolicyId;

    @Column(name = "server_name", length = 128)
    private String serverName;

    @Column(name = "capability_name", length = 256)
    private String capabilityName;

    @Column(name = "capability_type", length = 20)
    private String capabilityType;

    @Column(name = "protocol_method", length = 128)
    private String protocolMethod;

    @Column(name = "protocol_version", length = 20)
    private String protocolVersion;

    /**
     * Human-readable, event-specific note — e.g. "Server enabled" vs "Server disabled" under the same
     * SERVER_CONFIG_UPDATED event type, so the audit trail is meaningful without decoding the payload.
     * TEXT (not varchar): unbounded and cheap in Postgres for the occasional longer note.
     */
    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "request_payload", columnDefinition = "JSONB")
    private JsonNode requestPayload;

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "response_payload", columnDefinition = "JSONB")
    private JsonNode responsePayload;

    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "error_data", columnDefinition = "JSONB")
    private JsonNode errorData;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @Column(name = "schema_version", nullable = false, length = 10)
    @Builder.Default
    private String schemaVersion = "1.0";
}
