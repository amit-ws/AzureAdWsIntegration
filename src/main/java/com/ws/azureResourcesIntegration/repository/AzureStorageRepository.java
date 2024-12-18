package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureStorageAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureStorageRepository extends JpaRepository<AzureStorageAccount, Integer> {
    List<AzureStorageAccount> findAllByAzureTenant(AzureTenant azureTenant);
}
