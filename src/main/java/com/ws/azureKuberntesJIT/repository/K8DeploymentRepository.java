package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Deployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8DeploymentRepository extends JpaRepository<K8Deployment, Long> {
    List<K8Deployment> findAllByWsTenantNameAndCloudProviderType(String wsTenantName, CloudProviderType cloudProviderType);

    List<K8Deployment> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                         String clusterId, String namespace);

    List<K8Deployment> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);
    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);



}
