package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Node;
import com.ws.azureKuberntesJIT.enttity.K8StorageClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8StorageClassRepository extends JpaRepository<K8StorageClass, Long> {
    @Modifying
    @Query("DELETE FROM K8StorageClass WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);

    List<K8StorageClass> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdOrderByName(String wsTenantName, CloudProviderType cloudProviderType, String clusterId);

}
