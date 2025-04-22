package com.ws.mcpAccessMgmt.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;


@Entity
@Table(name = "policy_agent", schema = "mcp_data-mgmt")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PolicyAgent {
    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "policy_id")
    private Policy policy;

    @ManyToOne
    @JoinColumn(name = "agent_id")
    private Agent agent;
}

