package com.ws.azureResourcesIntegration.service;

import com.ws.azureAdIntegration.exception.K8ResourceException;
import com.ws.azureResourcesIntegration.entities.AzureKubernetesCluster;
import com.ws.azureResourcesIntegration.entities.AzureSubscription;
import com.ws.azureResourcesIntegration.repository.AzureKubernetesClusterRepository;
import com.ws.azureResourcesIntegration.repository.AzureSubscriptionRepository;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceDataService {
    final AzureSubscriptionRepository azureSubscriptionRepository;
    final AzureKubernetesClusterRepository azureKubernetesClusterRepository;

    @Autowired
    public AzureResourceDataService(AzureSubscriptionRepository azureSubscriptionRepository, AzureKubernetesClusterRepository azureKubernetesClusterRepository) {
        this.azureSubscriptionRepository = azureSubscriptionRepository;
        this.azureKubernetesClusterRepository = azureKubernetesClusterRepository;
    }


    public void deleteBySubscriptionIdsAndWsTenantName(List<Integer> ids, String wsTenantName) {
        azureSubscriptionRepository.deleteByIdInAndWsTenantName(ids, wsTenantName);
    }

    public AzureSubscription findAzureSubscriptionByIdAndWsTenantName(String subscriptionId, String wsTenantName) {
        return azureSubscriptionRepository.findByAzureSubscriptionIdAndWsTenantName(subscriptionId, wsTenantName).orElse(null);
    }

    public AzureKubernetesCluster findAksByAzureId(String clusterId) {
        return azureKubernetesClusterRepository.findByAzureId(clusterId)
                .orElseThrow(() -> new K8ResourceException(String.format("No %s cluster found with provided cluster ID", clusterId)));
    }

}
