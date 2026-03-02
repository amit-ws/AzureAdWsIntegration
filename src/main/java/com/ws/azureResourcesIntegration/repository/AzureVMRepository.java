package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.dto.AzureVmDTO;
import com.ws.azureResourcesIntegration.entities.AzureVM;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureVMRepository extends JpaRepository<AzureVM, Integer> {
    @Query("SELECT av FROM AzureVM av WHERE upper(av.instanceId) = upper(:instanceId) AND av.wsTenantName = :wsTenantName")
    Optional<AzureVM> findAzureVMUsingInstanceId(String instanceId, String wsTenantName);

    List<AzureVM> findAllByWsTenantName(String wsTenantName);

    @Query("SELECT DISTINCT av FROM AzureVM av INNER JOIN AzureRoleAssignment ara ON UPPER(av.instanceId) = UPPER(ara.scope) " +
            "WHERE ara.scopeType = :scopeType AND ara.principalType = :principalType and ara.assignee = :assignee and av.wsTenantName = :tenantName")
    List<AzureVM> getAzureVMsForPrinciple(String scopeType, String principalType, String assignee, String tenantName);

//    List<AzureVM> findAllByWsTenantNameAndIsPublishedTrue(String wsTenantName);

//    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureVMDTO(av.id, av.azureVmId, av.instanceId, av.name, av.computerName, " +
//            "av.powerState, av.size, av.osType, av.publicIpInstanceId, av.resourceGroupName, " +
//            "av.osDiskSize, av.region, av.securityType, av.resourceType, av.zones, " +
//            "av.resourceIdentityType, av.ipAddress, av.azureResourceGroup.id, av.azureSubscription.id," +
//            "av.isPublished, av.updatedAt, av.syncedAt, av.wsTenantName) " +
//            "FROM AzureVM av " +
//            "WHERE av.wsTenantName = :wsTenantName and av.isPublished")
//    List<AzureVMDTO> findAllAzureVmsUsingTenantName(String wsTenantName);


//    String wsTenantName;
//    Date syncedAt;
//    Date updatedAt;
//    String subscriptionId;
//    boolean isPublished;
    @Query(value = "SELECT new com.ws.azureResourcesIntegration.dto.AzureVmDTO(av.id, av.azureVmId, av.instanceId, av.name, av.computerName, av.powerState, " +
            "av.size, av.osType, av.publicIpInstanceId, av.resourceGroupName, av.osDiskSize, av.region, av.securityType, av.resourceType, av.zones, av. resourceIdentityType, " +
            "av.ipAddress, av.wsTenantName, av.syncedAt, av.updatedAt, av.subscriptionId, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM AzureVM av " +
            "LEFT JOIN PublishedResource pr ON upper(av.azureVmId) = upper(pr.resourceId) " +
            "WHERE av.wsTenantName = :wsTenantName AND (:subscriptionId IS NULL OR av.subscriptionId = :subscriptionId) " +
            "ORDER BY av.name")
    List<AzureVmDTO> findAllAzureVMUsingTenantNameAndSubscriptionId(String wsTenantName, String subscriptionId);

    @Query("SELECT av FROM AzureVM av " +
            "INNER JOIN PublishedResource pr ON av.azureVmId = pr.resourceId " +
            "WHERE av.wsTenantName = :wsTenantName AND (:subscriptionId IS NULL OR av.subscriptionId = :subscriptionId)  " +
            "ORDER BY av.name")
    List<AzureVM> findAllPublishedAzureVMByWsTenantNameAndSubscriptionId(String wsTenantName, String subscriptionId);

}

