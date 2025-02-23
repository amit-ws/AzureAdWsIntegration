package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Secret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8SecretRepository extends JpaRepository<K8Secret, Long> {
    List<K8Secret> findAllByWsTenantNameAndCloudProviderType(String wsTenantName, CloudProviderType cloudProviderType);

    List<K8Secret> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                String clusterId, String namespace);

    List<K8Secret> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);}
