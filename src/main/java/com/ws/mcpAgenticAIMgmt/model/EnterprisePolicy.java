package com.ws.mcpAgenticAIMgmt.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Builder
@Table(name = "enterprise_policy", schema = "ws_agentic_ai_iam")
@AllArgsConstructor
@NoArgsConstructor
public class EnterprisePolicy {
    @Id
    @GeneratedValue
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private UUID enterpriseId;

    @Column(columnDefinition = "boolean default false")
    private boolean inUse;
    private boolean active;

    @Builder.Default
    private Date createdAT = new Date();
    private Date updatedAT;

    @Column(nullable = false)
    private String policyName;
    private String description;
    private String version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER, mappedBy = "enterprisePolicy")
    private List<PolicyRule> policyRules;
}