package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Node;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8NodeRepository extends JpaRepository<K8Node, Long> {
    List<K8Node> findAllByWsTenantNameAndCloudProviderType(String wsTenantName, CloudProviderType cloudProviderType);

    List<K8Node> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                              String clusterId, String namespace);

    List<K8Node> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);
    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);

}
