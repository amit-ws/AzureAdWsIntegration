package com.ws.mcpAgenticAIMgmt.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Entity
@Table(name = "ws_agentic_ai", schema = "policy_condition")
@AllArgsConstructor
@NoArgsConstructor
public class PolicyCondition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String type;
    private String operator;
    private String value;
    private String startTime;
    private String endTime;
    private String timeZone;
    private String field;
    private Integer numericValue;
    private String name;

    @ElementCollection
    private List<String> values;

    private String pattern;
    private String startIP;
    private String endIP;

    @ManyToOne
    @JoinColumn(name = "rule_id")
    private PolicyRule policyRule;
}