//package com.ws.azureResourcesIntegration.dto;
//
//import com.ws.azureResourcesIntegration.entities.BaseAzureResource;
//import lombok.*;
//import lombok.experimental.FieldDefaults;
//
//import java.time.OffsetDateTime;
//import java.util.Date;
//
//@Data
//@NoArgsConstructor
//@FieldDefaults(level = AccessLevel.PRIVATE)
//public class AzureDatabaseDTO extends BaseAzureResource {
//    private Integer id;
//    private String azureDatabaseId;
//    private String databaseName;
//    private String azureServerId;
//    private String databaseType;
//    private String version;
//    private String status;
//    private Integer sizeInGb;
//    private OffsetDateTime lastBackupTime;
//    private OffsetDateTime createdDate;
//    private String edition;
//    private Long maxSizeBytes;
//    private String region;
//    private String dbStatus;
//    private String readScale;
//    private Double minCapacity;
//    private OffsetDateTime pausedDate;
//    private OffsetDateTime resumedDate;
//    private String defaultSecondaryLocation;
//    private String resourceType;
//    private String resourceGroupName;
//    Integer azureServerRowId;
//    Integer azureResourceGroupdId;
//    Integer wsAzureSubscriptionId;
//
//    public AzureDatabaseDTO(Integer id, String azureDatabaseId, String databaseName, String azureServerId,
//                            String databaseType, String version, String status, Integer sizeInGb,
//                            OffsetDateTime lastBackupTime, OffsetDateTime createdDate, String edition,
//                            Long maxSizeBytes, String region, String dbStatus, String readScale,
//                            Double minCapacity, OffsetDateTime pausedDate, OffsetDateTime resumedDate,
//                            String defaultSecondaryLocation, String resourceType, String resourceGroupName,
//                            Integer azureServerRowId, Integer azureResourceGroupdId, Integer wsAzureSubscriptionId,
//                            Boolean isPublished, Date updatedAt, Date syncedAt, String wsTenantName) {
//        super(isPublished, updatedAt, syncedAt, wsTenantName);
//        this.id = id;
//        this.azureDatabaseId = azureDatabaseId;
//        this.databaseName = databaseName;
//        this.azureServerId = azureServerId;
//        this.databaseType = databaseType;
//        this.version = version;
//        this.status = status;
//        this.sizeInGb = sizeInGb;
//        this.lastBackupTime = lastBackupTime;
//        this.createdDate = createdDate;
//        this.edition = edition;
//        this.maxSizeBytes = maxSizeBytes;
//        this.region = region;
//        this.dbStatus = dbStatus;
//        this.readScale = readScale;
//        this.minCapacity = minCapacity;
//        this.pausedDate = pausedDate;
//        this.resumedDate = resumedDate;
//        this.defaultSecondaryLocation = defaultSecondaryLocation;
//        this.resourceType = resourceType;
//        this.resourceGroupName = resourceGroupName;
//        this.azureServerRowId = azureServerRowId;
//        this.azureResourceGroupdId = azureResourceGroupdId;
//        this.wsAzureSubscriptionId = wsAzureSubscriptionId;
//    }
//
//}
