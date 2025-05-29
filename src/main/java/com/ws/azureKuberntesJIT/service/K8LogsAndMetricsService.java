package com.ws.azureKuberntesJIT.service;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.exception.K8ResourceException;
import com.ws.azureKuberntesJIT.dto.PageResponse;
import com.ws.azureKuberntesJIT.enttity.K8sLogEntry;
import com.ws.azureKuberntesJIT.repository.K8sLogEntryRepository;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;


@Slf4j
@Service
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class K8LogsAndMetricsService {

    final K8sLogEntryRepository k8sLogEntryRepository;

    @Autowired
    public K8LogsAndMetricsService(K8sLogEntryRepository k8sLogEntryRepository) {
        this.k8sLogEntryRepository = k8sLogEntryRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<K8sLogEntry> findK8sLogsForWsTenantUsingCloudType(String wsTenantName, CloudProviderType cloudType, int page, int size) {
        if (StringUtils.isEmpty(wsTenantName) || cloudType == null) {
            throw new K8ResourceException("Invalid input parameters: wsTenantName or cloudType is null or empty");
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<K8sLogEntry> logPage = k8sLogEntryRepository.findAllByWsTenantNameAndCloudProviderType(wsTenantName, cloudType, pageable);
        if (logPage == null || ObjectUtils.isEmpty(logPage.getContent())) {
            throw new K8ResourceException(String.format(
                    "No data found for the provided wsTenantName: %s and cloudType: %s",
                    wsTenantName, cloudType
            ));
        }

        return PageResponse.<K8sLogEntry>builder()
                .content(logPage.getContent())
                .page(logPage.getNumber())
                .size(logPage.getSize())
                .totalPages(logPage.getTotalPages())
                .totalElements(logPage.getTotalElements())
                .hasNext(logPage.hasNext())
                .build();
    }


}
