package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureDatabase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureDatabaseRepository extends JpaRepository<AzureDatabase, Integer> {
    List<AzureDatabase> findAllByWsTenantNameAndIsPublishedTrue(String wsTenantName);

//    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureDatabaseDTO(db.id, db.azureDatabaseId, db.databaseName, db.azureServerId, db.databaseType, db.version, db.status, db.sizeInGb," +
//            "db.lastBackupTime, db.lastBackupTime, db.edition, db.maxSizeBytes, db.region, db.dbStatus, db.readScale, db.minCapacity, db.pausedDate, db.resumedDate, " +
//            "db.defaultSecondaryLocation, db.resourceType, db.resourceGroupName, db.azureServer.id, db.azureServer.azureResourceGroup.id, db.azureServer.azureSubscription.id," +
//            "db.isPublished, db.updatedAt, db.syncedAt, db.wsTenantName) " +
//            "FROM AzureDatabase db WHERE db.wsTenantName = :wsTenantName and db.isPublished")
//    List<AzureDatabaseDTO> findAllAzureDatabasesByName(String wsTenantName);


}
