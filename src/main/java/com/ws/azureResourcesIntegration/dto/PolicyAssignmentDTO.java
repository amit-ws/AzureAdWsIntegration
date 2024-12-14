package com.ws.azureResourcesIntegration.dto;

import com.azure.resourcemanager.resources.models.EnforcementMode;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class PolicyAssignmentDTO {
    String id;
    String azureId;
    String displayName;
    String policyDefinitionId;
    String scope;
    String type;
    List<String> excludedScopes;
    String enforcementMode;
}
