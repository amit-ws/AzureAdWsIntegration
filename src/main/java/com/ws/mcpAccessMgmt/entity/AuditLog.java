package com.ws.mcpAccessMgmt.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.mcpAccessMgmt.constants.LogLevel;
import com.ws.mcpAccessMgmt.constants.LogType;
import com.ws.mcpAccessMgmt.constants.PolicyEffect;
import com.ws.mcpAccessMgmt.converter.JsonNodeConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_log", schema = "mcp_data-mgmt")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {
    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LogType logType; // [AUTHZ, DATA_ACCESS, SYSTEM, POLICY_CHANGE]

    @ManyToOne
    @JoinColumn(name = "agent_id")
    private Agent agent; // AI or Human-Proxy agent

    @Column(name = "external_user_id", length = 255)
    private String externalUserId; // Opaque ID from enterprise (e.g., "user-456")

    @Column(name = "source_ip")
    private String sourceIp; // IP of the requester

    private String httpMethod; // GET, POST, etc.

    @Column(name = "http_status_code")
    private Integer httpStatusCode; // 200, 403, 500

    @ManyToOne
    @JoinColumn(name = "resource_id")
    private Resource resource;

    private String action; // e.g., "read", "write", "delete"

    private String riskScore; // risk score about the Abent itself

    @Enumerated(EnumType.STRING)
    private PolicyEffect effect; // ALLOW, DENY, MASK

    @Column(name = "request_duration_ms")
    private Long requestDurationMs; // Time taken to process

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage; // Detailed error if any

    @Column(name = "session_id", length = 255)
    private String sessionId; // For tracing user sessions

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "JSONB")
    private JsonNode extendedContext; // Full request/response context

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion = "1.0"; // For future-proofing

    @CreationTimestamp
    private LocalDateTime timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_level", nullable = false)
    @Builder.Default
    private LogLevel logLevel = LogLevel.INFO;
}

