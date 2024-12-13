package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureStorage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureStorageRepository extends JpaRepository<AzureStorage, Integer> {
}
