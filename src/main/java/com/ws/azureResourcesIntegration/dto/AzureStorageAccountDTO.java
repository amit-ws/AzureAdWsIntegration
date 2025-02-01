package com.ws.azureResourcesIntegration.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;

@Data
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureStorageAccountDTO {
    Integer id;
    String azureStorageAccountId;
    String storageAccountName;
    String region;
    OffsetDateTime createdDate;
    String kind;
    String customDomainName;
    Boolean blobPublicAccessAllowed;
    Boolean sharedKeyAccessAllowed;
    Boolean isAccessAllowedFromAllNetworks;
    String publicNetworkAccess;
    String publicAccess;
    String skuTier;
    String accessTier;
    String resourceType;
    String resourceGroupName;
    String wsTenantName;
    Date syncedAt;
    Date updatedAt;
    String subscriptionId;
    boolean isPublished;
}
