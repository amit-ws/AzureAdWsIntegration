package com.ws.azureResourcesIntegration.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Date;

@Data
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor
public class AzureVmDTO {
    Integer id;
    String azureVmId;
    String instanceId;
    String name;
    String computerName;
    String powerState;
    String size;
    String osType;
    String publicIpInstanceId;
    String resourceGroupName;
    Integer osDiskSize;
    String region;
    String securityType;
    String resourceType;
    String zones;
    String resourceIdentityType;
    String ipAddress;
    String wsTenantName;
    Date syncedAt;
    Date updatedAt;
    String subscriptionId;
    boolean isPublished;
}
