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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_server", schema = "azure_test")
public class AzureServer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    UUID azureServerId;
    String serverName;
    String type;
    String serverVersion;
    String region;
    String resourceGroup;
    OffsetDateTime createdDate;
    String status;
    String kind;
    String state;
    Boolean managedServiceIdentityEnabled;
    String managedServiceIdentityType;
    String publicNetworkAccess;
    String resourceGroupName;
    String version;
    String innerModelState;
    String administratorType;
    String administratorSignInName;
    UUID administratorId;
    String location;
    String administratorLogin;
    UUID endpointConnectionId;
    UUID endpointId;
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
    @JsonIgnore
    @OneToMany(mappedBy = "azureServer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureDatabase> azureDatabases = new ArrayList<>();
}
