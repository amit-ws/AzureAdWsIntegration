package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureStorageAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureStorageRepository extends JpaRepository<AzureStorageAccount, Integer> {
    List<AzureStorageAccount> findAllByWsTenantName(String wsTenantName);

    @Query("SELECT DISTINCT asa FROM AzureStorageAccount asa INNER JOIN AzureRoleAssignment ara ON UPPER(asa.azureStorageAccountId) = UPPER(ara.scope) " +
            "WHERE ara.scopeType = :scopeType AND ara.principalType = :principalType and ara.assignee = :assignee and asa.wsTenantName = :tenantName")
    List<AzureStorageAccount> getAzureStorageAccountsForPrinciple(String scopeType, String principalType, String assignee, String tenantName);

    List<AzureStorageAccount> findAllByWsTenantNameAndIsPublishedTrue(String wsTenantName);

//    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureStorageAccountDTO(" +
//            "sa.id, sa.azureStorageAccountId, sa.storageAccountName, sa.region, sa.createdDate, sa.kind, " +
//            "sa.customDomainName, sa.blobPublicAccessAllowed, sa.sharedKeyAccessAllowed, sa.isAccessAllowedFromAllNetworks, " +
//            "sa.publicNetworkAccess, sa.publicAccess, sa.skuTier, sa.accessTier, sa.resourceType, sa.resourceGroupName, " +
//            "sa.azureResourceGroup.id, sa.azureSubscription.id, sa.isPublished, sa.updatedAt, sa.syncedAt, sa.wsTenantName) " +
//            "FROM AzureStorageAccount sa " +
//            "WHERE sa.wsTenantName = :wsTenantName and sa.isPublished")
//    List<AzureStorageAccountDTO> findAllAzureStorageAccountsByName(String wsTenantName);


}
