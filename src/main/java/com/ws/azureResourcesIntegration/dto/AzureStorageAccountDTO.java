//package com.ws.azureResourcesIntegration.dto;
//
//import com.ws.azureResourcesIntegration.entities.BaseAzureResource;
//import lombok.AccessLevel;
//import lombok.AllArgsConstructor;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//import lombok.experimental.FieldDefaults;
//
//import java.time.OffsetDateTime;
//import java.util.Date;
//
//@Data
//@NoArgsConstructor
//@FieldDefaults(level = AccessLevel.PRIVATE)
//public class AzureStorageAccountDTO extends BaseAzureResource {
//    private Integer id;
//    private String azureStorageAccountId;
//    private String storageAccountName;
//    private String region;
//    private OffsetDateTime createdDate;
//    private String kind;
//    private String customDomainName;
//    private Boolean blobPublicAccessAllowed;
//    private Boolean sharedKeyAccessAllowed;
//    private Boolean isAccessAllowedFromAllNetworks;
//    private String publicNetworkAccess;
//    private String publicAccess;
//    private String skuTier;
//    private String accessTier;
//    private String resourceType;
//    private String resourceGroupName;
//    private Integer azureResourceGroupId;
//    private Integer azureSubscriptionId;
//
//    public AzureStorageAccountDTO(Integer id, String azureStorageAccountId, String storageAccountName, String region,
//                                  OffsetDateTime createdDate, String kind, String customDomainName,
//                                  Boolean blobPublicAccessAllowed, Boolean sharedKeyAccessAllowed,
//                                  Boolean isAccessAllowedFromAllNetworks, String publicNetworkAccess,
//                                  String publicAccess, String skuTier, String accessTier,
//                                  String resourceType, String resourceGroupName,
//                                  Integer azureResourceGroupId, Integer azureSubscriptionId,
//                                  Boolean isPublished, Date updatedAt, Date syncedAt, String wsTenantName) {
//        super(isPublished, updatedAt, syncedAt, wsTenantName);
//        this.id = id;
//        this.azureStorageAccountId = azureStorageAccountId;
//        this.storageAccountName = storageAccountName;
//        this.region = region;
//        this.createdDate = createdDate;
//        this.kind = kind;
//        this.customDomainName = customDomainName;
//        this.blobPublicAccessAllowed = blobPublicAccessAllowed;
//        this.sharedKeyAccessAllowed = sharedKeyAccessAllowed;
//        this.isAccessAllowedFromAllNetworks = isAccessAllowedFromAllNetworks;
//        this.publicNetworkAccess = publicNetworkAccess;
//        this.publicAccess = publicAccess;
//        this.skuTier = skuTier;
//        this.accessTier = accessTier;
//        this.resourceType = resourceType;
//        this.resourceGroupName = resourceGroupName;
//        this.azureResourceGroupId = azureResourceGroupId;
//        this.azureSubscriptionId = azureSubscriptionId;
//    }
//}
