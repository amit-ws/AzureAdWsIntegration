package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureStorageAccount;
import com.ws.azureResourcesIntegration.entities.AzureVM;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureStorageRepository extends JpaRepository<AzureStorageAccount, Integer> {
    List<AzureStorageAccount> findAllByAzureTenant(AzureTenant azureTenant);

    @Query("SELECT DISTINCT asa FROM AzureStorageAccount asa INNER JOIN AzureRoleAssignment ara ON UPPER(asa.azureStorageAccountId) = UPPER(ara.scope) " +
            "WHERE ara.scopeType = :scopeType AND ara.principalType = :principalType and ara.assignee = :assignee and asa.azureTenant = :azureTenant")
    List<AzureStorageAccount> getAzureStorageAccountsForPrinciple(String scopeType, String principalType, String assignee, AzureTenant azureTenant);

    List<AzureStorageAccount> findAllByWsTenantNameAndIsPublishedTrue(String wsTenantName);

    Optional<AzureStorageAccount> findByIdAndResourceType(Integer id, String resourceType);

}
