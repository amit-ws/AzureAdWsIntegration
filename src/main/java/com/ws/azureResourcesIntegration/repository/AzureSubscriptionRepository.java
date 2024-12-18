package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureSubscriptionRepository extends JpaRepository<AzureSubscription, Integer> {
    void deleteByAzureTenant(AzureTenant azureTenant);
}
