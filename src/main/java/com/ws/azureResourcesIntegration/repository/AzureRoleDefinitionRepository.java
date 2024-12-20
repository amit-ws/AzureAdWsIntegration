package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureRoleDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureRoleDefinitionRepository extends JpaRepository<AzureRoleDefinition, Integer> {
    void deleteAllByAzureTenant(AzureTenant azureTenant);
}
