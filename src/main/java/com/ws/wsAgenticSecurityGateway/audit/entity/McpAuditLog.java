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
@Table(name = "mcp_audit_log", schema = "ws_agentic_security",
        indexes = {
                @Index(name = "idx_audit_event_type", columnList = "event_type"),
                @Index(name = "idx_audit_module", columnList = "module"),
                @Index(name = "idx_audit_status", columnList = "status"),
                @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
                @Index(name = "idx_audit_correlation_id", columnList = "correlation_id"),
                @Index(name = "idx_audit_server_name", columnList = "server_name"),
                @Index(name = "idx_audit_session_id", columnList = "session_id"),
                @Index(name = "idx_audit_request_id", columnList = "request_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpAuditLog {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

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

    @Column(name = "pdp_reason", length = 1024)
    private String pdpReason;

    @Column(name = "server_name", length = 128)
    private String serverName;

    @Column(name = "capability_name", length = 256)
    private String capabilityName;

    @Column(name = "capability_type", length = 20)
    private String capabilityType;

    @Column(name = "mcp_method", length = 128)
    private String mcpMethod;

    @Column(name = "protocol_version", length = 20)
    private String protocolVersion;

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
