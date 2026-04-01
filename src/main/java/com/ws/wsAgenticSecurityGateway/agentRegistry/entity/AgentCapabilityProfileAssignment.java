package com.ws.wsAgenticSecurityGateway.agentRegistry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_capability_profile_assignment", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_agent_profile",
                        columnNames = {"agent_id", "profile_id", "ws_tenant_name"})
        },
        indexes = {
                @Index(name = "idx_cap_assign_agent", columnList = "agent_id"),
                @Index(name = "idx_cap_assign_profile", columnList = "profile_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentCapabilityProfileAssignment {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @CreationTimestamp
    @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt;
}
