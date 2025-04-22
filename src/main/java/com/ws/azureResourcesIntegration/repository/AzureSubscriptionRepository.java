package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureSubscriptionRepository extends JpaRepository<AzureSubscription, Integer> {
    void deleteByAzureTenant(AzureTenant azureTenant);

    @Query("SELECT azs FROM AzureSubscription azs " +
            "WHERE azs.wsTenantName = :waTenantName  AND (:subscriptionId IS NULL OR azs.azureSubscriptionId = :subscriptionId)  " +
            "ORDER BY azs.subscriptionName ")
    List<AzureSubscription> findAllByWsTenantNameAndSubscriptionId(String waTenantName, String subscriptionId);

    Optional<AzureSubscription> findByAzureSubscriptionIdAndWsTenantName(String subscriptionId, String wsTenantName);

    @Modifying
    void deleteByIdInAndWsTenantName(List<Integer> ids, String wsTenantName);
}
