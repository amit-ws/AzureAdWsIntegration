package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureServerRepository extends JpaRepository<AzureServer, Integer> {
    void deleteAllByAzureTenant(AzureTenant azureTenant);
}
