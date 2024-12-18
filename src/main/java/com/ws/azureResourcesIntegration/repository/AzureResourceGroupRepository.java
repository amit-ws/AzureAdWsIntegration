package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureResourceGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureResourceGroupRepository extends JpaRepository<AzureResourceGroup, Integer> {
}
