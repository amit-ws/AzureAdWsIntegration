package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureSubscriptionRepository extends JpaRepository<AzureSubscription, Integer> {
    void deleteByAzureTenant(AzureTenant azureTenant);

    List<AzureSubscription> findAllByWsTenantName(String wsTenantName);

    Optional<AzureSubscription> findByAzureSubscriptionIdAndWsTenantName(String subscriptionId, String wsTenantName);

    @Modifying
    void deleteByIdInAndWsTenantName(List<Integer> ids, String wsTenantName);
}
