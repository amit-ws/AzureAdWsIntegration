package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.dto.AzureKubernetesClusterDTO;
import com.ws.azureResourcesIntegration.entities.AzureKubernetesCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AzureKubernetesClusterRepository extends JpaRepository<AzureKubernetesCluster, Long> {
    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureKubernetesClusterDTO(aks.id, aks.azureId, aks.name, aks.regionName, " +
            "aks.isAzureRbacEnabled, aks.type, aks.subscriptionId, aks.resourceGroupName, aks.wsTenantName, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM AzureKubernetesCluster aks " +
            "LEFT JOIN PublishedResource pr ON UPPER(aks.azureId) = upper(pr.resourceId) " +
            "WHERE aks.wsTenantName = :wsTenantName AND (:subscriptionId IS NULL OR aks.subscriptionId = :subscriptionId)  " +
            "ORDER BY aks.name")
    List<AzureKubernetesClusterDTO> findAllAzureKubernetesClustersUsingWsTenantNameAndSubscriptionId(String wsTenantName, String subscriptionId);


    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureKubernetesClusterDTO(aks.id, aks.azureId, aks.name, aks.regionName, " +
            "aks.isAzureRbacEnabled, aks.type, aks.subscriptionId, aks.resourceGroupName, aks.wsTenantName, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM AzureKubernetesCluster aks " +
            "INNER JOIN PublishedResource pr ON UPPER(aks.azureId) = upper(pr.resourceId) " +
            "WHERE aks.wsTenantName = :wsTenantName AND (:subscriptionId IS NULL OR aks.subscriptionId = :subscriptionId)  " +
            "ORDER BY aks.name")
    List<AzureKubernetesClusterDTO> findAllPublishedAksClustersByWsTenantNameAndSubscriptionId(String wsTenantName, String subscriptionId);

//    List<AzureKubernetesCluster> findAllByWsTenantNameAndPowerState(String wsTenantName, String powerState);

    List<AzureKubernetesCluster> findAllByWsTenantNameAndSubscriptionIdInAndPowerState(String wsTenantName, Collection<String> subscriptionIds, String powerState);

    Optional<AzureKubernetesCluster> findByName(String name);

    Optional<AzureKubernetesCluster> findByAzureId(String clusterId);
}
