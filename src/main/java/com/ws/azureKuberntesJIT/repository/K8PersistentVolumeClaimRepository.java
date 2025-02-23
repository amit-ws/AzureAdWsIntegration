package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8PersistentVolumeClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

@Repository
public interface K8PersistentVolumeClaimRepository extends JpaRepository<K8PersistentVolumeClaim, Long> {
    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);
}
