package com.ws.mcpAgenticAIMgmt.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Entity
@Table(name = "ws_agentic_ai", schema = "policy_rule")
@AllArgsConstructor
@NoArgsConstructor
public class PolicyRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ruleId;

    private String description;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "target_id")
    private PolicyTarget policyTarget;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "rule_id")
    private List<PolicyCondition> conditions;

    private String effect;

    @ManyToOne
    @JoinColumn(name = "policy_id")
    private EnterprisePolicy enterprisePolicy;
}