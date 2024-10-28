package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AzureTenantRepository extends JpaRepository<AzureTenant, Integer> {
    Optional<AzureTenant> findByAzureId(String azureTenantId);

    Optional<AzureTenant> findByWsTenantId(Integer wsTenantId);

    void deleteByAzureId(String azureTenantId);


}
