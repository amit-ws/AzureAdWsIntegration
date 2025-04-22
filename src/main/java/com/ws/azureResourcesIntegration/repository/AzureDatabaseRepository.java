package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.dto.AzureDatabaseDTO;
import com.ws.azureResourcesIntegration.entities.AzureDatabase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureDatabaseRepository extends JpaRepository<AzureDatabase, Integer> {
//    List<AzureDatabase> findAllByWsTenantNameAndIsPublishedTrue(String wsTenantName);

    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureDatabaseDTO(db.id, db.azureDatabaseId, db.databaseName, db.azureServerId, db.databaseType, " +
            "db.version, db.status, db.sizeInGb, db.lastBackupTime, db.lastBackupTime, db.edition, db.maxSizeBytes, db.region, db.dbStatus, db.readScale, " +
            "db.minCapacity, db.pausedDate, db.resumedDate, db.defaultSecondaryLocation, db.resourceType, db.resourceGroupName, " +
            "db.wsTenantName, db.syncedAt, db.updatedAt, db.subscriptionId, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM AzureDatabase db " +
            "LEFT JOIN PublishedResource pr ON UPPER(db.azureDatabaseId) = upper(pr.resourceId) " +
            "WHERE db.wsTenantName = :wsTenantName  AND (:subscriptionId IS NULL OR db.subscriptionId = :subscriptionId)  " +
            "ORDER BY db.databaseName")
    List<AzureDatabaseDTO> findAllAzureDatabasesUsingWsTenantNameAndSubscriptionId(String wsTenantName, String subscriptionId);

    @Query("SELECT db FROM AzureDatabase db " +
            "INNER JOIN PublishedResource pr ON db.azureDatabaseId = pr.resourceId " +
            "WHERE db.wsTenantName = :wsTenantName AND (:subscriptionId IS NULL OR db.subscriptionId = :subscriptionId)  " +
            "ORDER BY db.databaseName")
    List<AzureDatabase> findAllPublishedAzureDatabaseBywsTenantNameAndsubscriptionId(String wsTenantName, String subscriptionId);


}
