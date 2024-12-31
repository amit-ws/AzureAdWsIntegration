package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureDatabase;
import com.ws.azureResourcesIntegration.entities.AzureStorageAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureDatabaseRepository extends JpaRepository<AzureDatabase, Integer> {
    List<AzureDatabase> findAllByWsTenantNameAndIsPublishedTrue(String wsTenantName);


}
