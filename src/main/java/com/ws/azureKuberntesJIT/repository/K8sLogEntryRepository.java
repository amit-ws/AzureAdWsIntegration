package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8sLogEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8sLogEntryRepository extends JpaRepository<K8sLogEntry, Long> {

    Page<K8sLogEntry> findAllByWsTenantNameAndCloudProviderType(String wsTenantName, CloudProviderType cloudType, Pageable pageable);
}
