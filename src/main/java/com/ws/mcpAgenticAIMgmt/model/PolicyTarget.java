package com.ws.mcpAgenticAIMgmt.model;

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
@Table(name = "policy_target", schema = "ws_agentic_ai_iam")
@AllArgsConstructor
@NoArgsConstructor
public class PolicyTarget {
    @Id
    @GeneratedValue
    @Builder.Default
    private UUID id = UUID.randomUUID();

    private String agentId;
    private String resourceType;
    private String resource;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "kpolicy_target_actions",
            schema = "ws_agentic_ai_iam",
            joinColumns = @JoinColumn(name = "policy_target_id")
    )
    @Column(name = "action")
    private List<String> action;

}