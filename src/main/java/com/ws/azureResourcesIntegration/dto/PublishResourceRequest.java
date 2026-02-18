package com.ws.azureResourcesIntegration.dto;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureResourcesIntegration.constant.PublishResourceType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class PublishResourceRequest {
    @NotNull(message = "Kubernetes resource ID is required")
    String resourceId;
    @NotNull(message = "WS tenant name is required")
    String wsTenantName;
    boolean flag;
    @NotNull(message = "Resource account ID is required. Eg: SubscriptionId for Azure, ProjectId for GCP, AccountId for AWS")
    String resourceAccountId;
    @NotNull(message = "resource type is required. Eg: VIRTUAL_MACHINE, CLUSTER_ROLE")
    PublishResourceType type;
    CloudProviderType cloudProviderType; /* [NOT NULL] for K8 resources types */
    String clusterId; /* [NOT NULL] for K8 resources types */
}
