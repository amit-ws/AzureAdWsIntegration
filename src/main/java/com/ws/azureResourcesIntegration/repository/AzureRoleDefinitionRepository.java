package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureRoleDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureRoleDefinitionRepository extends JpaRepository<AzureRoleDefinition, Integer> {
    void deleteAllByAzureTenant(AzureTenant azureTenant);
    List<AzureRoleDefinition> findAllByAzureTenant(AzureTenant azureTenant);
    Optional<AzureRoleDefinition> findByIdAndAzureTenant(Integer id, AzureTenant azureTenant);
}
