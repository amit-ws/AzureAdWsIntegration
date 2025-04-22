package com.ws.mcpAgenticAIMgmt.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Entity
@Table(name = "ws_agentic_ai", schema = "enterprise_policy")
@AllArgsConstructor
@NoArgsConstructor
public class EnterprisePolicy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String policyName;
    private String description;
    private String version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "policy_id")
    private List<PolicyRule> policyRules;
}