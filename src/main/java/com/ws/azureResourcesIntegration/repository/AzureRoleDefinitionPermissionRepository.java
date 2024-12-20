package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureRoleDefinitionPermission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AzureRoleDefinitionPermissionRepository extends JpaRepository<AzureRoleDefinitionPermission, Integer> {
}
