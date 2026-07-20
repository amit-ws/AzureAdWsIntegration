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

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_template", nullable = false)
    @Builder.Default
    private Boolean isTemplate = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<AgentCapabilityProfileRule> rules = new ArrayList<>();
}
