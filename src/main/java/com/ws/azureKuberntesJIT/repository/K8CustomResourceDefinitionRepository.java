package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8CustomResourceDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8CustomResourceDefinitionRepository extends JpaRepository<K8CustomResourceDefinition, Long> {
    List<K8CustomResourceDefinition> findAllByWsTenantNameAndCloudProviderType(String wsTenantName, CloudProviderType cloudProviderType);
    List<K8CustomResourceDefinition> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdOrderByName(String wsTenantNamed, CloudProviderType cloudProviderType, String clusterId);
    @Modifying
    @Query("DELETE FROM K8CustomResourceDefinition WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);
}
