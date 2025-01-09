package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureDatabase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureDatabaseRepository extends JpaRepository<AzureDatabase, Integer> {
    List<AzureDatabase> findAllByWsTenantNameAndIsPublishedTrue(String wsTenantName);
    Optional<AzureDatabase> findByIdAndResourceType(Integer id, String resourceType);

}
