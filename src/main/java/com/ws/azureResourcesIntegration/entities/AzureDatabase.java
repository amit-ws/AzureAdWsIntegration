package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_database", schema = "azure_test")
public class AzureDatabase extends BaseAzureResource{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String instanceId;  /* Path (instance) ID  */
    String azureDatabaseId; /* UUID of the database (sent by azure) */
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


//    @JsonIgnore
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "ws_azure_resource_group_id", referencedColumnName = "id")
//    AzureResourceGroup azureResourceGroup;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_server_id", referencedColumnName = "id")
    AzureServer azureServer;

//    @JsonIgnore
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
//    AzureTenant azureTenant;
}
