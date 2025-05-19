package com.ws.mcpAccessMgmt.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.mcpAccessMgmt.constants.ResourceType;
import com.ws.mcpAccessMgmt.constants.SensitivityLevel;
import com.ws.mcpAccessMgmt.converter.JsonNodeConverter;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "resource", schema = "mcp_data-mgmt")
public class Resource {
    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name; // e.g., "patient_records"

    @Enumerated(EnumType.STRING)
    private ResourceType type; // e.g., DATABASE, API, FILE

    @Enumerated(EnumType.STRING)
    private SensitivityLevel sensitivity; // PUBLIC, INTERNAL, CONFIDENTIAL

    @ManyToOne
    @JoinColumn(name = "enterprise_id", nullable = false)
    private McpEnterprise mcpEnterprise;

    // Optional metadata
    @Column(columnDefinition = "JSONB")
    @Convert(converter = JsonNodeConverter.class)
    private JsonNode tags; // e.g., {"department": "finance", "owner": "team-alpha"}
}

