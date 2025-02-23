package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8ConfigMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8ConfigMapRepository extends JpaRepository<K8ConfigMap, Long> {
    List<K8ConfigMap> findAllByWsTenantNameAndCloudProviderType(String wsTenantName, CloudProviderType cloudProviderType);

    List<K8ConfigMap> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                   String clusterId, String namespace);

    List<K8ConfigMap> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);
    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);

}