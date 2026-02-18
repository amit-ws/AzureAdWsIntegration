package com.ws.mcpAccessMgmt.entity;

import com.ws.mcpAccessMgmt.constants.PolicyEffect;
import com.ws.mcpAccessMgmt.constants.PolicyType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "policy", schema = "mcp_data-mgmt")
@NoArgsConstructor
@AllArgsConstructor
@Data
public class Policy {
    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "enterprise_id", nullable = false)
    private McpEnterprise mcpEnterprise;

    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private PolicyType type; // RESOURCE, AGENT, HYBRID

    @Enumerated(EnumType.STRING)
    private PolicyEffect effect; // ALLOW, DENY, MASK

    private int priority;
    private boolean isActive;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToMany
    List<PolicyCondition> conditions = new ArrayList<>();
}

