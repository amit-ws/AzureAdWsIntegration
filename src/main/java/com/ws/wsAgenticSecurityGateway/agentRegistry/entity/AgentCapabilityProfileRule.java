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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    private AgentCapabilityProfile profile;

    @Column(name = "server_config_name", nullable = false, length = 256)
    private String serverConfigName;

    @Column(name = "capability_type", nullable = false, length = 20)
    @Builder.Default
    private String capabilityType = "ALL";

    @Column(name = "mode", nullable = false, length = 20)
    private String mode;

    @Column(name = "capability_names", columnDefinition = "TEXT")
    private String capabilityNames;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
