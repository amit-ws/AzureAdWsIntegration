package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureVM;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureVMRepository extends JpaRepository<AzureVM, Integer> {
    List<AzureVM> findAllByAzureTenant(AzureTenant azureTenant);

    @Query("SELECT DISTINCT av FROM AzureVM av INNER JOIN AzureRoleAssignment ara ON UPPER(av.instanceId) = UPPER(ara.scope) " +
            "WHERE ara.scopeType = :scopeType AND ara.principalType = :principalType and ara.assignee = :assignee and av.azureTenant = :azureTenant")
    List<AzureVM> getAzureVMsForPrinciple(String scopeType, String principalType, String assignee, AzureTenant azureTenant);

    List<AzureVM> findAllByWsTenantNameAndIsPublishedTrue(String wsTenantName);
}
