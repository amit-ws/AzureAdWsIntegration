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

/**
 * Persistent audit record for every observable event across the WS MCP Gateway.
 *
 * <p>Covers all five auditable areas:
 * <ol>
 *   <li>AI Client  &lt;-&gt;  WS Server (Agent-Facing)</li>
 *   <li>WS Client  &lt;-&gt;  Enterprise MCP Server (Server-Facing)</li>
 *   <li>Capability Registry CRUD</li>
 *   <li>Orchestration Layer</li>
 * </ol>
 *
 * <p>PDP (Policy Decision Point) audit records live in a separate
 * {@code pdp_audit_log} table, joined by {@code correlation_id}.
 *
 * <p>All fields are intentionally non-truncated.  JSONB columns hold the
 * complete request / response / context payloads as required by the spec.
 */
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

    // ── Event Identity ────────────────────────────────────────────────

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

    // ── Correlation & Tracing ─────────────────────────────────────────

    /** Links all audit records belonging to a single end-to-end invocation. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** MCP session identifier (WS Server-side or WS Client-side). */
    @Column(name = "session_id", length = 128)
    private String sessionId;

    /** Agent's JSON-RPC request id — links audit records to specific agent requests. */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /** Deterministic ordering within a correlation chain (1-based, null for non-orchestration events). */
    @Column(name = "event_sequence")
    private Integer eventSequence;

    /** AI agent name (e.g., "claude-desktop", "cursor") — set for agent-facing + orchestration events. */
    @Column(name = "agent_name", length = 256)
    private String agentName;

    // ── Authentication & Identity Context ───────────────────────────────

    /** Authentication method: "OAUTH2" or "NONE". */
    @Column(name = "auth_method", length = 32)
    private String authMethod;

    /** JWT subject (service account UUID or human UUID from IdP). */
    @Column(name = "auth_identity", length = 256)
    private String authIdentity;

    /** Human username from JWT preferred_username (null for AUTOMATED_AGENT). */
    @Column(name = "user_identity", length = 256)
    private String userIdentity;

    /** Token classification: "AUTOMATED_AGENT" or "HUMAN_DELEGATED". */
    @Column(name = "token_type", length = 32)
    private String tokenType;

    /** OAuth2 client_id (azp claim) — verified agent identity from JWT. */
    @Column(name = "agent_client_id", length = 256)
    private String agentClientId;

    /** All roles from JWT (realm + client roles merged). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_roles", columnDefinition = "jsonb")
    private List<String> agentRoles;

    /** Human user UUID from gateway_human_users — set when tokenType is HUMAN_DELEGATED. */
    @Column(name = "human_user_id", length = 64)
    private String humanUserId;

    /** PDP evaluation decision: "ALLOWED" or "DENIED". */
    @Column(name = "pdp_decision", length = 16)
    private String pdpDecision;

    /** PDP evaluation reason or matched policy name. */
    @Column(name = "pdp_reason", length = 1024)
    private String pdpReason;

    // ── Server / Capability Context ───────────────────────────────────

    /** Config-level name of the enterprise MCP server (e.g. "github"). */
    @Column(name = "server_name", length = 128)
    private String serverName;

    /** Name of the tool / resource / prompt involved, if applicable. */
    @Column(name = "capability_name", length = 256)
    private String capabilityName;

    /** TOOL / RESOURCE / PROMPT. */
    @Column(name = "capability_type", length = 20)
    private String capabilityType;

    // ── MCP Protocol Details ──────────────────────────────────────────

    /** JSON-RPC method name (e.g. "initialize", "tools/call"). */
    @Column(name = "mcp_method", length = 128)
    private String mcpMethod;

    /** JSON-RPC protocol version (e.g. "2024-11-05"). */
    @Column(name = "protocol_version", length = 20)
    private String protocolVersion;

    // ── Request / Response Payloads (full, no truncation) ─────────────

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "request_payload", columnDefinition = "JSONB")
    private JsonNode requestPayload;

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "response_payload", columnDefinition = "JSONB")
    private JsonNode responsePayload;

    // ── Error Context (MCP-compliant) ─────────────────────────────────

    /** JSON-RPC error code (-32700, -32600, -32601, -32602, -32603, etc.). */
    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "error_data", columnDefinition = "JSONB")
    private JsonNode errorData;

    // ── Timing & Metadata ─────────────────────────────────────────────

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @Column(name = "schema_version", nullable = false, length = 10)
    @Builder.Default
    private String schemaVersion = "1.0";
}
