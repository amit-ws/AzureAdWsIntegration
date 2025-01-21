package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureVM;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureVMRepository extends JpaRepository<AzureVM, Integer> {
    List<AzureVM> findAllByWsTenantName(String wsTenantName);

    @Query("SELECT DISTINCT av FROM AzureVM av INNER JOIN AzureRoleAssignment ara ON UPPER(av.instanceId) = UPPER(ara.scope) " +
            "WHERE ara.scopeType = :scopeType AND ara.principalType = :principalType and ara.assignee = :assignee and av.wsTenantName = :tenantName")
    List<AzureVM> getAzureVMsForPrinciple(String scopeType, String principalType, String assignee, String tenantName);

        List<AzureVM> findAllByWsTenantNameAndIsPublishedTrue(String wsTenantName);

//    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureVMDTO(av.id, av.azureVmId, av.instanceId, av.name, av.computerName, " +
//            "av.powerState, av.size, av.osType, av.publicIpInstanceId, av.resourceGroupName, " +
//            "av.osDiskSize, av.region, av.securityType, av.resourceType, av.zones, " +
//            "av.resourceIdentityType, av.ipAddress, av.azureResourceGroup.id, av.azureSubscription.id," +
//            "av.isPublished, av.updatedAt, av.syncedAt, av.wsTenantName) " +
//            "FROM AzureVM av " +
//            "WHERE av.wsTenantName = :wsTenantName and av.isPublished")
//    List<AzureVMDTO> findAllAzureVmsUsingTenantName(String wsTenantName);

}

