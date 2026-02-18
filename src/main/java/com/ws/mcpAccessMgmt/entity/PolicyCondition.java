package com.ws.mcpAccessMgmt.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.mcpAccessMgmt.constants.ConditionOperator;
import com.ws.mcpAccessMgmt.constants.LogicalOperator;
import com.ws.mcpAccessMgmt.converter.JsonNodeConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "policy_condition", schema = "mcp_data-mgmt")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PolicyCondition {
    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    private String attribute; // e.g., "resource.sensitivity", "agent.role"

    @Enumerated(EnumType.STRING)
    private ConditionOperator operator; // EQUALS, IN, GREATER_THAN, etc.

    private String value; // e.g., "CONFIDENTIAL", "['admin', 'diagnosis_ai']"

    @Enumerated(EnumType.STRING)
    private LogicalOperator logicalOperator; // AND, OR
}

