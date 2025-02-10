package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureK8ClusterCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureK8ClusterCredentialRepository extends JpaRepository<AzureK8ClusterCredential, Integer> {
}
