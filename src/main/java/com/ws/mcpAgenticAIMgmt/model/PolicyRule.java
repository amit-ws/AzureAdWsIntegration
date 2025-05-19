package com.ws.mcpAgenticAIMgmt.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Entity
@Builder
@Table(name = "policy_rule", schema = "ws_agentic_ai_iam")
@AllArgsConstructor
@NoArgsConstructor
public class PolicyRule {
    @Id
    @GeneratedValue
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private String ruleName;

    private String description;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "target_id")
    private PolicyTarget policyTarget;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY, mappedBy = "policyRule")
    private List<PolicyRuleCondition> conditions;

    private String effect;

    @ManyToOne
    @JsonIgnore
    private EnterprisePolicy enterprisePolicy;
}