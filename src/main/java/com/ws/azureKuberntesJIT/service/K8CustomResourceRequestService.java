package com.ws.azureKuberntesJIT.service;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.models.K8CustomResourceRequestDTO;
import com.ws.azureKuberntesJIT.repository.K8CustomResourceRequestRepository;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8CustomResourceRequestService {
    final K8CustomResourceRequestRepository k8CustomResourceRequestRepository;
    final K8ResourceService k8ResourceService;

    @Autowired
    public K8CustomResourceRequestService(K8CustomResourceRequestRepository k8CustomResourceRequestRepository, K8ResourceService k8ResourceService) {
        this.k8CustomResourceRequestRepository = k8CustomResourceRequestRepository;
        this.k8ResourceService = k8ResourceService;
    }

    public void revokeCustomRequestsByWsTenantNameAndSubscriptionIds(String wsTenantName, CloudProviderType cloudType, Collection<String> subscriptionIds) {
        List<K8CustomResourceRequestDTO> customRequests = k8CustomResourceRequestRepository.findK8CustomResourceRequestUsingWsTenantAndCloudIDs(wsTenantName, cloudType, subscriptionIds, RequestStatus.APPROVED);
        if (!CollectionUtils.isNotEmpty(customRequests)) {
            k8ResourceService.revokeK8ResourceAccess(customRequests);
        }
        k8CustomResourceRequestRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudResourceAccountIdIn(wsTenantName, cloudType, subscriptionIds);
        log.info(String.format("Total %s K8CustomResourceRequest revoked for ws tenant: %s", customRequests.size(), wsTenantName));
    }
}
