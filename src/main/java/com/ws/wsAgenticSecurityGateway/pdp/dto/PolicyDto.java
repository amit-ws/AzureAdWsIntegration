package com.ws.wsAgenticSecurityGateway.pdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for policy CRUD operations via REST API.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyDto {

    private UUID id;
    private String policyName;
    private String description;
    private String cedarPolicyId;
    private String policyText;
    private String effect;
    private Boolean enabled;
    private Integer priority;
    private String tags;
    private String source;
    private String originalPrompt;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
