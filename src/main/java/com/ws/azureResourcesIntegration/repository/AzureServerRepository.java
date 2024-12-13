package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureServerRepository extends JpaRepository<AzureServer, Integer> {
}
