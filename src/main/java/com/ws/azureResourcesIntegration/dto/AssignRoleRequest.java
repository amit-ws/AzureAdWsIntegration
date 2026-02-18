package com.ws.azureResourcesIntegration.dto;

import com.ws.azureAdIntegration.constants.AzurePrincipleType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class AssignRoleRequest {
    @NotNull(message = "tenant name is required")
    String tenantName;
    @NotNull(message = "Subscription ID cannot be null")
    String subscriptionId;
    @NotNull(message = "Resource Scope is required")
    String resourceScope;
    @NotNull(message = "Principle (assignee) ID is required")
    String principleId;
    @NotEmpty(message = "Role definition ID(s) is required")
    Set<String> roleDefinitionPathIds;
    @NotNull(message = "Principle (assignee) type is required")
    AzurePrincipleType principleType;
    @Positive(message = "Expiry time amount must be greater than 0 if specified")
    Long expiryTimeAmount;  /* null -> means, unlimited time*/
    @NotNull(message = "User email is required")
    String userEmail; /* Person who raised the request (from AzureUserConfigure Entity)*/
    String description;



    String roleDefinitionPathId;

    //    @NotNull(message = "Subscription ID is required")
//    Integer subscriptionId;
}
