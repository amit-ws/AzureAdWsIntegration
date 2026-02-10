package com.ws.wsAgenticSecurity.registry.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurity.audit.converter.JsonNodeColumnConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a registered enterprise MCP server in the capability registry.
 *
 * <p>Each server record corresponds to a single entry in the MCP client
 * configuration (e.g. "github", "jira") and groups all capabilities
 * (tools, resources, prompts) discovered from that server.
 */
@Entity
@Table(name = "mcp_server", schema = "ws_agentic_security",
        indexes = {
                @Index(name = "idx_mcp_server_config_name", columnList = "server_config_name", unique = true),
                @Index(name = "idx_mcp_server_status", columnList = "status")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpServerEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    /** Config-level name of the server (e.g. "github", "jira"). Unique. */
    @Column(name = "server_config_name", nullable = false, unique = true, length = 128)
    private String serverConfigName;

    /** Display name reported by the server during initialization. */
    @Column(name = "display_name", length = 256)
    private String displayName;

    /** Server version reported during initialization. */
    @Column(name = "version", length = 256)
    private String version;

    /** MCP protocol version negotiated during handshake. */
    @Column(name = "protocol_version", length = 20)
    private String protocolVersion;

    /** Server status — ACTIVE when connected, INACTIVE when disconnected. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    /** Full server capabilities as reported during initialization (JSONB). */
    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "capabilities", columnDefinition = "JSONB")
    private JsonNode capabilities;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private LocalDateTime registeredAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
