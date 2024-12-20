package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureRoleDefinitionAction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AzureRoleDefinitionActionRepository extends JpaRepository<AzureRoleDefinitionAction, Integer> {
}
