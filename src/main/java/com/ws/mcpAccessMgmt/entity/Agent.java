package com.ws.mcpAccessMgmt.entity;

import com.ws.mcpAccessMgmt.constants.AgentType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent", schema = "mcp_data-mgmt")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Agent {
    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "external_id", nullable = false, unique = true)
    private String externalId; // Unique ID from enterprise (e.g., "diabetes_analyzer_v3")

    private String name; // Optional: "Diabetes Analyzer"

    @Enumerated(EnumType.STRING)
    private AgentType type; // AI or HUMAN_PROXY

    @ManyToOne
    @JoinColumn(name = "enterprise_id", nullable = false)
    private McpEnterprise mcpEnterprise;

    @CreationTimestamp
    private LocalDateTime createdAt;
}

