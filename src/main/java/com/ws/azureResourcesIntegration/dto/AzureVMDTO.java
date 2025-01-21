//package com.ws.azureResourcesIntegration.dto;
//
//import com.ws.azureResourcesIntegration.entities.BaseAzureResource;
//import lombok.AccessLevel;
//import lombok.AllArgsConstructor;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//import lombok.experimental.FieldDefaults;
//
//import java.util.Date;
//
//@Data
//@NoArgsConstructor
//@FieldDefaults(level = AccessLevel.PRIVATE)
//public class AzureVMDTO extends BaseAzureResource {
//    Integer id;
//    String azureVmId;
//    String instanceId;
//    String name;
//    String computerName;
//    String powerState;
//    String size;
//    String osType;
//    String publicIpInstanceId;
//    String resourceGroupName;
//    Integer osDiskSize;
//    String region;
//    String securityType;
//    String resourceType;
//    String zones;
//    String resourceIdentityType;
//    String ipAddress;
//    Integer azureResourceGroupdId;
//    Integer wsAzureSubscriptionId;
//
//    public AzureVMDTO(Integer id, String azureVmId, String instanceId, String name, String computerName,
//                      String powerState, String size, String osType, String publicIpInstanceId,
//                      String resourceGroupName, Integer osDiskSize, String region, String securityType,
//                      String resourceType, String zones, String resourceIdentityType, String ipAddress,
//                      Integer azureResourceGroupdId, Integer wsAzureSubscriptionId,
//                      Boolean isPublished, Date updatedAt, Date syncedAt, String wsTenantName) {
//        super(isPublished, updatedAt, syncedAt, wsTenantName);
//        this.id = id;
//        this.azureVmId = azureVmId;
//        this.instanceId = instanceId;
//        this.name = name;
//        this.computerName = computerName;
//        this.powerState = powerState;
//        this.size = size;
//        this.osType = osType;
//        this.publicIpInstanceId = publicIpInstanceId;
//        this.resourceGroupName = resourceGroupName;
//        this.osDiskSize = osDiskSize;
//        this.region = region;
//        this.securityType = securityType;
//        this.resourceType = resourceType;
//        this.zones = zones;
//        this.resourceIdentityType = resourceIdentityType;
//        this.ipAddress = ipAddress;
//        this.azureResourceGroupdId = azureResourceGroupdId;
//        this.wsAzureSubscriptionId = wsAzureSubscriptionId;
//    }
//}
