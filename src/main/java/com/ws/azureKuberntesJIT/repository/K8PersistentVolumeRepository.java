package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8PersistentVolume;
import com.ws.azureKuberntesJIT.enttity.K8StorageClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8PersistentVolumeRepository extends JpaRepository<K8PersistentVolume, Long> {
    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);

    List<K8PersistentVolume> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdOrderByName(String wsTenantName, CloudProviderType cloudProviderType, String clusterId);

}
