package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.UUID;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_server", schema = "azure_test")
public class AzureDatabase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    UUID azureDatabaseId;
    String databaseName;
    UUID azureServerId;
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
    java.time.OffsetDateTime pausedDate;
    java.time.OffsetDateTime resumedDate;
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
