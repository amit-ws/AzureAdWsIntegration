package com.ws.azureResourcesIntegration.dto;

import com.ws.azureAdIntegration.constants.AzurePrincipleType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class AssignRoleRequest {
    @NotNull(message = "tenant name is required")
    String tenantName;
    @NotNull(message = "Subscription ID is required")
    String subscriptionId;
    @NotNull(message = "Resource Scope is required")
    String resourceScope;
    @NotNull(message = "Role definition ID is required")
    String roleDefinitionId;
    @NotNull(message = "Principle (assignee) ID is required")
    String principleId;
    @NotNull(message = "Principle (assignee) type is required")
    AzurePrincipleType principleType;
    @Positive(message = "Expiry time amount must be greater than 0 if specified")
    Long expiryTimeAmount;  /* null -> means, unlimited time*/
    String description;
}
