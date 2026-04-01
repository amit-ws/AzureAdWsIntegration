package com.ws.wsAgenticSecurityGateway.agentRegistry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reusable Capability Access Profile — like an IAM Role for MCP agents.
 *
 * <p>Each profile contains server-level rules that define which capabilities
 * (tools, prompts, resources) an agent can see and call. Profiles are assigned
 * to agents via {@link AgentCapabilityProfileAssignment}.
 *
 * <p><strong>Default deny</strong>: agents with no assigned profiles see
 * NO capabilities. Admin must explicitly assign a profile to grant access.
 */
@Entity
@Table(name = "agent_capability_profile", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_cap_profile_name",
                        columnNames = {"name", "ws_tenant_name"})
        },
        indexes = {
                @Index(name = "idx_cap_profile_name", columnList = "name")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentCapabilityProfile {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    /** Human-readable profile name (e.g., "OCR Processing", "Read Only"). */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** Optional description of what this profile grants. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** True for built-in template profiles (Full Access, Read Only, etc.). */
    @Column(name = "is_template", nullable = false)
    @Builder.Default
    private Boolean isTemplate = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Server-level rules within this profile. */
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<AgentCapabilityProfileRule> rules = new ArrayList<>();
}
