package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8PersistentVolume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface K8PersistentVolumeRepository extends JpaRepository<K8PersistentVolume, Long> {
}
