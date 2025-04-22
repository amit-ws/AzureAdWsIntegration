package com.ws.mcpAccessMgmt.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.mcpAccessMgmt.constants.ActionType;
import com.ws.mcpAccessMgmt.converter.JsonNodeConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "policy_action", schema = "mcp_data-mgmt")
@NoArgsConstructor
@AllArgsConstructor
@Data
public class PolicyAction {
    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    @Enumerated(EnumType.STRING)
    private ActionType actionType; // MASK, REDACT, TRUNCATE

    private String targetField; // e.g., "ssn", "email"

    @Convert(converter = JsonNodeConverter.class)
    private JsonNode config; // JSONB: {"mask_char": "*", "visible_chars": 4}
}

