package com.ws.azureResourcesIntegration.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureKubernetesClusterDTO {
    Integer id;
    String azureId;
    String name;
    String regionName;
    boolean isAzureRbacEnabled;
    String type;
    String subscriptionId;
    String resourceGroupName;
    String wsTenantName;
}