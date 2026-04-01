package com.ws.wsAgenticSecurityGateway.capabilityRegistry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistent record of a tool discovered from an enterprise MCP server.
 *
 * <p>The {@code publicName} is the namespaced identifier exposed to AI agents
 * (e.g. {@code github.create_issue}). The {@code toolName} is the original
 * name as reported by the enterprise server (e.g. {@code create_issue}).
 */
@Entity
@Table(name = "mcp_tool", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_mcp_tool_public_name",
                        columnNames = {"public_name", "ws_tenant_name"})
        },
        indexes = {
                @Index(name = "idx_mcp_tool_public_name", columnList = "public_name"),
                @Index(name = "idx_mcp_tool_server_id", columnList = "server_id"),
                @Index(name = "idx_mcp_tool_tool_name", columnList = "tool_name")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpToolEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    /** Owning server reference. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private McpServerEntity server;

    /** Original tool name as reported by the enterprise MCP server. */
    @Column(name = "tool_name", nullable = false, length = 256)
    private String toolName;

    /** Namespaced public name: {@code <serverConfigName>.<toolName>}. Unique. */
    @Column(name = "public_name", nullable = false, length = 512)
    private String publicName;

    /** Human-readable description of the tool. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** JSON Schema for the tool's input parameters (stored as text, not JSONB). */
    @Column(name = "input_schema", columnDefinition = "TEXT")
    private String inputSchema;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private LocalDateTime registeredAt;
}
