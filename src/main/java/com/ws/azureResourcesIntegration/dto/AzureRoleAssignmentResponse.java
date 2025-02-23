package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureRoleAssignmentResponse {
    String assignmentAzureId;
    String roleName;
}