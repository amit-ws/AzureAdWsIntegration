package com.ws.azureResourcesIntegration.dto;

import com.ws.azureResourcesIntegration.entities.BaseAzureResource;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;

@Data
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureDatabaseDTO {
    Integer id;
    String azureDatabaseId;
    String databaseName;
    String azureServerId;
    String databaseType;
    String version;
    String status;
    Integer sizeInGb;
    OffsetDateTime lastBackupTime;
    OffsetDateTime createdDate;
    String edition;
    Long maxSizeBytes;
    String region;
    String dbStatus;
    String readScale;
    Double minCapacity;
    OffsetDateTime pausedDate;
    OffsetDateTime resumedDate;
    String defaultSecondaryLocation;
    String resourceType;
    String resourceGroupName;
    String wsTenantName;
    Date syncedAt;
    Date updatedAt;
    String subscriptionId;
    boolean isPublished;
}
