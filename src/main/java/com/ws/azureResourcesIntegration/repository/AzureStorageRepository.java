package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.dto.AzureStorageAccountDTO;
import com.ws.azureResourcesIntegration.entities.AzureStorageAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureStorageRepository extends JpaRepository<AzureStorageAccount, Integer> {
    List<AzureStorageAccount> findAllByWsTenantName(String wsTenantName);

    @Query("SELECT DISTINCT asa FROM AzureStorageAccount asa INNER JOIN AzureRoleAssignment ara ON UPPER(asa.azureStorageAccountId) = UPPER(ara.scope) " +
            "WHERE ara.scopeType = :scopeType AND ara.principalType = :principalType and ara.assignee = :assignee and asa.wsTenantName = :tenantName")
    List<AzureStorageAccount> getAzureStorageAccountsForPrinciple(String scopeType, String principalType, String assignee, String tenantName);

//    List<AzureStorageAccount> findAllByWsTenantNameAndIsPublishedTrue(String wsTenantName);

//    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureStorageAccountDTO(" +
//            "sa.id, sa.azureStorageAccountId, sa.storageAccountName, sa.region, sa.createdDate, sa.kind, " +
//            "sa.customDomainName, sa.blobPublicAccessAllowed, sa.sharedKeyAccessAllowed, sa.isAccessAllowedFromAllNetworks, " +
//            "sa.publicNetworkAccess, sa.publicAccess, sa.skuTier, sa.accessTier, sa.resourceType, sa.resourceGroupName, " +
//            "sa.azureResourceGroup.id, sa.azureSubscription.id, sa.isPublished, sa.updatedAt, sa.syncedAt, sa.wsTenantName) " +
//            "FROM AzureStorageAccount sa " +
//            "WHERE sa.wsTenantName = :wsTenantName and sa.isPublished")
//    List<AzureStorageAccountDTO> findAllAzureStorageAccountsByName(String wsTenantName);


//    Integer id, String azureStorageAccountId, String storageAccountName, String region,
//    OffsetDateTime createdDate, String kind, String customDomainName,
//    Boolean blobPublicAccessAllowed, Boolean sharedKeyAccessAllowed,
//    Boolean isAccessAllowedFromAllNetworks, String publicNetworkAccess,
//    String publicAccess, String skuTier, String accessTier,
//    String resourceType, String resourceGroupName,
//    boolean isPublished, Date updatedAt, Date syncedAt, String wsTenantName


    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureStorageAccountDTO(asa.id, asa.azureStorageAccountId, asa.storageAccountName, asa.region, " +
            "asa.createdDate, asa.kind, asa.customDomainName, asa.blobPublicAccessAllowed, asa.sharedKeyAccessAllowed, asa.isAccessAllowedFromAllNetworks, asa.publicNetworkAccess, " +
            "asa.publicAccess, asa.skuTier, asa.accessTier, asa.resourceType, asa.resourceGroupName, " +
            "asa.wsTenantName, asa.syncedAt, asa.updatedAt, asa.subscriptionId, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM AzureStorageAccount asa " +
            "LEFT JOIN PublishedResource pr ON upper(asa.storageAccountName) = upper(pr.resourceId) " +
            "WHERE asa.wsTenantName = :wsTenantName  AND (:subscriptionId IS NULL OR asa.subscriptionId = :subscriptionId) " +
            "ORDER BY asa.storageAccountName")
    List<AzureStorageAccountDTO> findAllAzureStorageAccountsUsingTenantNameAndSubscriptionId(String wsTenantName, String subscriptionId);

    @Query("SELECT asa FROM AzureStorageAccount asa " +
            "INNER JOIN PublishedResource pr ON asa.storageAccountName = pr.resourceId " +
            "WHERE asa.wsTenantName = :wsTenantName  AND (:subscriptionId IS NULL OR asa.subscriptionId = :subscriptionId) " +
            "ORDER BY asa.storageAccountName")
    List<AzureStorageAccount> findAllPublishedAzureStorageAccountsBywsTenantNameAndsubscriptionId(String wsTenantName, String subscriptionId);


}
