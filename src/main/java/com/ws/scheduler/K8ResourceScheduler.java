package com.ws.scheduler;

import com.ws.azureKuberntesJIT.enttity.K8CustomResourceRequest;
import com.ws.azureKuberntesJIT.repository.K8CustomResourceRequestRepository;
import com.ws.azureKuberntesJIT.service.K8ResourceService;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class K8ResourceScheduler {
    final K8CustomResourceRequestRepository k8CustomResourceRequestRepository;
    final K8ResourceService k8ResourceService;

    public K8ResourceScheduler(K8CustomResourceRequestRepository k8CustomResourceRequestRepository, K8ResourceService k8ResourceService) {
        this.k8CustomResourceRequestRepository = k8CustomResourceRequestRepository;
        this.k8ResourceService = k8ResourceService;
    }


    @Scheduled(cron = "*/20 * * * * *")
    private void removeAzureResourcesAccess() {
        List<K8CustomResourceRequest> customResourceRequests = k8CustomResourceRequestRepository.findAllByStatus(RequestStatus.APPROVED);
        long count = customResourceRequests.stream()
                .filter(customRequest -> LocalDateTime.now().isAfter(customRequest.getValidTo()))
                .peek(k8ResourceService::revokeK8ResourceAccess)
                .count();
        if (count > 0) {
            log.info(String.format("Total %s k8 custom requests removed at %s", count, LocalDateTime.now()));
        }
    }
}
