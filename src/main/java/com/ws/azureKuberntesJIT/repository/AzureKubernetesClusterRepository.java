package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.AzureKubernetesCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureKubernetesClusterRepository extends JpaRepository<AzureKubernetesCluster, Long> {
}
