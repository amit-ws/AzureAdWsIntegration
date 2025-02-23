package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Namespace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8NamespaceRepository extends JpaRepository<K8Namespace, Long> {
    List<K8Namespace> findAllByWsTenantNameAndCloudProviderType(String wsTenantName, CloudProviderType cloudProviderType);
    List<K8Namespace> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdOrderByName(String wsTenantName, CloudProviderType cloudProviderType, String clusterId);
    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);

}
