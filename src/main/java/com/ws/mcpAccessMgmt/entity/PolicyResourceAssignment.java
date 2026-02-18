package com.ws.mcpAccessMgmt.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "policy_resource_assignment", schema = "mcp_data-mgmt")
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class PolicyResourceAssignment {
    @Id
    @GeneratedValue(generator = "UUID")
    private UUID uuid;

    @ManyToOne
    @JoinColumn(name = "policy_id")
    private Policy policy;

    @ManyToOne
    @JoinColumn(name = "resource_id")
    private Resource resource;

    @Column(name = "is_excluded")
    @Builder.Default
    private Boolean isExcluded = false; // e.g., Override to exclude a resource
}

