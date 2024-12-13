package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureSubscriptionRepository extends JpaRepository<AzureSubscription, Integer> {
}
