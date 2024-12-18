package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_database", schema = "azure_test-backup")
public class AzureDatabase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_server_id", referencedColumnName = "id")
    AzureServer azureServer;
}
