package com.ws.wsAgenticSecurityGateway.agentRegistry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Server-level rule within a {@link AgentCapabilityProfile}.
 *
 * <p>Each rule targets a specific MCP server and defines which capability
 * types are allowed and in what mode:
 * <ul>
 *   <li>{@code INCLUDE_ALL} — all capabilities of the specified type from this server</li>
 *   <li>{@code INCLUDE_ONLY} — only the listed capability names</li>
 *   <li>{@code EXCLUDE} — all except the listed capability names</li>
 * </ul>
 *
 * <p>The {@code capabilityType} field controls which type of capability
 * the rule applies to: {@code ALL}, {@code TOOL}, {@code PROMPT}, or {@code RESOURCE}.
 */
@Entity
@Table(name = "agent_capability_profile_rule", schema = "ws_agentic_security",
        indexes = {
                @Index(name = "idx_cap_rule_profile", columnList = "profile_id"),
                @Index(name = "idx_cap_rule_server", columnList = "server_config_name")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentCapabilityProfileRule {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    /** Parent profile this rule belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    private AgentCapabilityProfile profile;

    /** The MCP server this rule targets (matches gateway_server_config.name). */
    @Column(name = "server_config_name", nullable = false, length = 256)
    private String serverConfigName;

    /** Which capability type this rule applies to: ALL, TOOL, PROMPT, RESOURCE. */
    @Column(name = "capability_type", nullable = false, length = 20)
    @Builder.Default
    private String capabilityType = "ALL";

    /** Rule mode: INCLUDE_ALL, INCLUDE_ONLY, EXCLUDE. */
    @Column(name = "mode", nullable = false, length = 20)
    private String mode;

    /**
     * Comma-separated original capability names (before namespace prefix).
     * Null for INCLUDE_ALL mode.
     * Example: "search_code,get_file_contents,list_repos"
     */
    @Column(name = "capability_names", columnDefinition = "TEXT")
    private String capabilityNames;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
