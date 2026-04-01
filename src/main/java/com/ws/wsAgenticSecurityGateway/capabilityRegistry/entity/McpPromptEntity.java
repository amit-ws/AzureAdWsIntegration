package com.ws.wsAgenticSecurityGateway.capabilityRegistry.entity;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Persistent record of a prompt discovered from an enterprise MCP server.
 *
 * <p>The {@code publicName} is the namespaced identifier exposed to AI agents
 * (e.g. {@code github.code_review_prompt}). The {@code promptName} is the
 * original name as reported by the enterprise server.
 */
@Entity
@Table(name = "mcp_prompt", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_mcp_prompt_public_name",
                        columnNames = {"public_name", "ws_tenant_name"})
        },
        indexes = {
                @Index(name = "idx_mcp_prompt_public_name", columnList = "public_name"),
                @Index(name = "idx_mcp_prompt_server_id", columnList = "server_id"),
                @Index(name = "idx_mcp_prompt_name", columnList = "prompt_name")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpPromptEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    /** Owning server reference. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private McpServerEntity server;

    /** Original prompt name as reported by the enterprise MCP server. */
    @Column(name = "prompt_name", nullable = false, length = 256)
    private String promptName;

    /** Namespaced public name: {@code <serverConfigName>.<promptName>}. Unique. */
    @Column(name = "public_name", nullable = false, length = 512)
    private String publicName;

    /** Human-readable description of the prompt. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Prompt argument definitions as JSON (JSONB). */
    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "arguments", columnDefinition = "JSONB")
    private JsonNode arguments;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private LocalDateTime registeredAt;
}
