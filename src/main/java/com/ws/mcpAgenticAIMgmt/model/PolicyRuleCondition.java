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
@Table(name = "policy_rule_condition", schema = "ws_agentic_ai_iam")
@AllArgsConstructor
@NoArgsConstructor
public class PolicyRuleCondition {
    @Id
    @GeneratedValue
    @Builder.Default
    private UUID id = UUID.randomUUID();

    private String type;
    private String operator;
    private String value;
    private String startTime;
    private String endTime;
    private String timeZone;

    @ManyToOne
    @JsonIgnore
    private PolicyRule policyRule;

//    @ElementCollection
//    private List<String> values;
//    private String field;
//    private Integer numericValue;
//    private String name;
//
//    private String pattern;
//    private String startIP;
//    private String endIP;
}