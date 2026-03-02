package com.ws.wsAgenticSecurityGateway.wsClient.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.audit.converter.JsonNodeColumnConverter;
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
 * Persists MCP server configurations in the database.
 *
 * <p>Replaces the file-based {@code mcp_config.json} approach — the admin
 * configures servers via REST API ({@code /api/admin/mcp-servers}), and
 * this entity stores the connection parameters (URL, headers, timeout).
 *
 * <p>Header values may contain {@code ${env:VAR_NAME}} patterns that are
 * resolved at connect time from system environment variables.
 */
@Entity
@Table(name = "gateway_server_config", schema = "ws_agentic_security",
        indexes = {
                @Index(name = "idx_server_config_name", columnList = "server_name", unique = true),
                @Index(name = "idx_server_config_enabled", columnList = "enabled")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayServerConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Unique config-level name (e.g. "github", "atlassian"). Used as key everywhere. */
    @Column(name = "server_name", nullable = false, unique = true, length = 128)
    private String serverName;

    /** Server type — currently only "http" supported. */
    @Column(name = "type", nullable = false, length = 20)
    @Builder.Default
    private String type = "http";

    /** Base URL for the MCP server endpoint. */
    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    /** HTTP headers as JSONB. Values may contain ${env:VAR_NAME} patterns. */
    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "headers", columnDefinition = "JSONB")
    private JsonNode headers;

    /** Additional server-specific config parameters as JSONB. */
    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "server_config", columnDefinition = "JSONB")
    private JsonNode serverConfig;

    /** Connection timeout in seconds. */
    @Column(name = "timeout_seconds")
    @Builder.Default
    private Integer timeoutSeconds = 30;

    /** Whether this server config is active. Disabled servers are skipped on startup. */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** Whether to auto-connect this server on gateway startup. */
    @Column(name = "auto_connect", nullable = false)
    @Builder.Default
    private Boolean autoConnect = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
