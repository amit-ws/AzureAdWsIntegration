package com.ws.mcpAccessMgmt.entity;

import com.ws.mcpAccessMgmt.constants.PolicyModel;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "enterprise", schema = "mcp_data-mgmt")
public class McpEnterprise {
    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private PolicyModel policyModel; // RESOURCE, AGENT, HYBRID

    @CreationTimestamp
    private LocalDateTime createdAt;
}

