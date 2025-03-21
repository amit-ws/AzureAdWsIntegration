package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.dto.AzureKubernetesClusterDTO;
import com.ws.azureResourcesIntegration.entities.AzureKubernetesCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public interface AzureKubernetesClusterRepository extends JpaRepository<AzureKubernetesCluster, Long> {
    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureKubernetesClusterDTO(aks.id, aks.azureId, aks.name, aks.regionName, " +
            "aks.isAzureRbacEnabled, aks.type, aks.subscriptionId, aks.resourceGroupName, aks.wsTenantName) " +
            "FROM AzureKubernetesCluster aks WHERE aks.wsTenantName = :wsTenantName")
    List<AzureKubernetesClusterDTO> findAllAzureKubernetesClustersUsingWsTenantName(String wsTenantName);

//    List<AzureKubernetesCluster> findAllByWsTenantNameAndPowerState(String wsTenantName, String powerState);

    List<AzureKubernetesCluster> findAllByWsTenantNameAndSubscriptionIdInAndPowerState(String wsTenantName, Collection<String> subscriptionIds, String powerState);

    Optional<AzureKubernetesCluster> findByName(String name);

    Optional<AzureKubernetesCluster> findByAzureId(String clusterId);
}
