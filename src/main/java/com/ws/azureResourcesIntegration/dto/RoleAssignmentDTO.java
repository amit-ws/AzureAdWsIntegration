package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class RoleAssignmentDTO {
    String roleAssignmentId;
    String name;
    String description;
    String assignee;
    String scope;
    String condition;
    String assignedRoleDefinitionId;
    String createdBy;
    String type;
    String principalType;
}

