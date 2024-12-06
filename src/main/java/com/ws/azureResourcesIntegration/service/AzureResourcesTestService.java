package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.azure.resourcemanager.authorization.models.RoleDefinition;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.resources.models.Subscription;
import com.azure.resourcemanager.sql.models.ServerPrivateEndpointConnection;
import com.azure.resourcemanager.sql.models.SqlDatabase;
import com.azure.resourcemanager.sql.models.SqlServer;
import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureResourcesIntegration.configuration.AzureAuthConfigurationFactory;
import com.ws.azureResourcesIntegration.dto.*;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourcesTestService {
    final String clientId = "f741d2f8-8ec5-4246-9051-";
    final String clientSecret = "C6n8Q~Pe3lYUXaRp6gLNOZUK~";
    final String tenantId = "0079de83-6146-45cb-a189-8";
    final String subscriptionId = "15b85f1d-1983-469c-a593-";
    final AzureAuthConfigurationFactory azureAuthConfigurationFactory;

    @Autowired
    public AzureResourcesTestService(AzureAuthConfigurationFactory azureAuthConfigurationFactory) {
        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
    }

    private AzureResourceManager getAzureResourceManager() {
        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }

    private AzureResourceManager getAzureResourceManager(String clientId, String clientSecret, String tenantId) {
        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId);
    }

    private GraphServiceClient getGraphServiceClient() {
        return azureAuthConfigurationFactory.createAzureGraphServiceClient(clientId, clientSecret, tenantId);
    }


    public Collection<VmDTO> listVMs() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<VirtualMachine> vms = azureResourceManager.virtualMachines().list();
        return StreamSupport.stream(vms.spliterator(), false)
                .map(vm -> VmDTO.builder()
                        .vmId(vm.vmId())
                        .instanceId(vm.id())
                        .name(vm.name())
                        .computerName(vm.computerName())
                        .powerState(vm.powerState().toString())
                        .size(vm.size().getValue())
                        .osType(vm.osType().toString())
                        .publicIPInstanceId(vm.getPrimaryPublicIPAddressId())
                        .resourceGroupName(vm.resourceGroupName())
                        .osDiskSize(vm.osDiskSize())
                        .region(vm.region().name())
                        .securityType(vm.securityType().toString())
                        .type(vm.type())
                        .zones(vm.innerModel().zones())
                        .resourceIdentityType(vm.innerModel().identity() != null ? vm.innerModel().identity().type().name() : null)
                        .ipAddress(vm.getPrimaryPublicIPAddress().ipAddress())
                        .build())
                .collect(Collectors.toList());
    }

    public Collection<ResourceGroupDTO> listResourceGroups() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<ResourceGroup> resourceGroups = azureResourceManager.resourceGroups().list();
        return StreamSupport.stream(resourceGroups.spliterator(), false)
                .map(resourceGroup -> ResourceGroupDTO.builder()
                        .id(resourceGroup.id())
                        .name(resourceGroup.name())
                        .regionName(resourceGroup.regionName())
                        .build())
                .collect(Collectors.toList());
    }


    public Collection<StorageAccountDTO> listStorageAccounts() {
        List<StorageAccountDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        azureResourceManager.storageAccounts().list().forEach(storageAccount -> {
            azureResourceManager.storageBlobContainers()
                    .list(storageAccount.resourceGroupName(), storageAccount.name())
                    .forEach(container -> {
                        StorageAccountDTO storageDetails = StorageAccountDTO.builder()
                                .storageAccountId(storageAccount.id())
                                .storageAccountName(storageAccount.name())
                                .region(storageAccount.region().toString())
                                .createdDate(storageAccount.creationTime())
                                .kind(storageAccount.kind().toString())
                                .customDomainName(storageAccount.customDomain() != null ? storageAccount.customDomain().name() : null)
                                .blobPublicAccessAllowed(storageAccount.isBlobPublicAccessAllowed())
                                .sharedKeyAccessAllowed(storageAccount.isSharedKeyAccessAllowed())
                                .isAccessAllowedFromAllNetworks(storageAccount.isAccessAllowedFromAllNetworks())
                                .publicAccess(storageAccount.publicNetworkAccess().toString())
                                .containerName(container.name())
                                .publicAccess(container.publicAccess() != null ? container.publicAccess().toString() : null)
                                .containerType(container.type() != null ? container.type().toString() : null) // Handle null container type
                                .build();
                        response.add(storageDetails);
                    });
        });
        return response;
    }


    public Collection<RoleDefinitionDTO> listRBACRoles() {
        List<RoleDefinitionDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<RoleDefinition> roles = azureResourceManager.accessManagement().roleDefinitions().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        roles.forEach(role -> {
            List<PermissionDTO> permissions = new ArrayList<>();
            if (role.permissions() != null) {
                role.permissions().forEach(permission -> {
                    permissions.add(PermissionDTO.builder()
                            .actions(permission.actions())
                            .notActions(permission.notActions())
                            .build());
                });
            }
            response.add(RoleDefinitionDTO.builder()
                    .roleId(role.id())
                    .name(role.name())
                    .roleName(role.roleName())
                    .description(role.description())
                    .isCustomRole(isCustomRole(role.innerModel().roleType()))
                    .permissions(permissions)
                    .type(role.type())
                    .roleType(role.innerModel().roleType())
                    .assignableScopes(role.assignableScopes())
                    .createdBy(role.innerModel().createdBy())
                    .build());
        });
        return response;
    }


    public Collection<RoleAssignmentDTO> listRoleAssignments() {
        List<RoleAssignmentDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<RoleAssignment> assignments = azureResourceManager.accessManagement().roleAssignments().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        assignments.forEach(assignment -> {
            RoleAssignmentDTO roleAssignmentDTO = RoleAssignmentDTO.builder()
                    .roleAssignmentId(assignment.id())
                    .assignedRoleDefinitionId(assignment.roleDefinitionId())
                    .name(assignment.name())
                    .description(assignment.description())
                    .assignee(assignment.principalId())
                    .scope(assignment.scope())
                    .condition(assignment.condition())
                    .createdBy(assignment.innerModel().createdBy())
                    .type(assignment.innerModel().type())
                    .principalType(assignment.innerModel().principalType().toString())
                    .build();
            response.add(roleAssignmentDTO);
        });
        return response;
    }


    public List<DBServerDTO> listAllServerWithDBsForTenant() {
        List<DBServerDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        for (SqlServer sqlServer : azureResourceManager.sqlServers().list()) {
            log.info("SQL Server Kind: {}", sqlServer.kind());
            log.info("SQL Server State: {}", sqlServer.state());
            log.info("SQL Server Managed Service Identity Enabled: {}", sqlServer.isManagedServiceIdentityEnabled());
            log.info("SQL Server Managed Service Identity Type: {}", sqlServer.managedServiceIdentityType());
            log.info("SQL Server Public Network Access: {}", sqlServer.publicNetworkAccess());
            if (sqlServer.getActiveDirectoryAdministrator() != null) {
                log.info("SQL Server Active Directory Administrator Type: {}", sqlServer.getActiveDirectoryAdministrator().administratorType());
                log.info("SQL Server Active Directory Administrator Sign-in Name: {}", sqlServer.getActiveDirectoryAdministrator().signInName());
                log.info("SQL Server Active Directory Administrator ID: {}", sqlServer.getActiveDirectoryAdministrator().id());
            }
            log.info("SQL Server DNS Aliases: {}", sqlServer.dnsAliases());
            log.info("SQL Server Resource Group Name: {}", sqlServer.resourceGroupName());
            log.info("SQL Server Version: {}", sqlServer.version());
            log.info("SQL Server Inner Model State: {}", sqlServer.innerModel().state());

            if (sqlServer.innerModel().privateEndpointConnections() != null) {
                for (ServerPrivateEndpointConnection privateEndpointConnection : sqlServer.innerModel().privateEndpointConnections()) {
                    log.info("SQL Server Private Endpoint Connection ID: {}", privateEndpointConnection.id());
                    log.info("SQL Server Private Endpoint ID: {}", privateEndpointConnection.properties().privateEndpoint().id());
                }
            }

            log.info(" ");
            for (SqlDatabase sqlDatabase : sqlServer.databases().list()) {
                log.info("Edition: {}", sqlDatabase.edition());
                log.info("Max Size (Bytes): {}", sqlDatabase.maxSizeBytes());
                log.info("Region: {}", sqlDatabase.region());
                log.info("Status: {}", sqlDatabase.innerModel().status().toString());
                log.info("Read Scale: {}", sqlDatabase.innerModel().readScale().toString());
                log.info("Min Capacity: {}", sqlDatabase.innerModel().minCapacity());
                log.info("Paused Date: {}", sqlDatabase.innerModel().pausedDate());
                log.info("Resumed Date: {}", sqlDatabase.innerModel().resumedDate());
            }
        }
        azureResourceManager.sqlServers().list().forEach(sqlServer -> {
            DBServerDTO serverDTO = DBServerDTO.builder()
                    .serverId(sqlServer.id())
                    .serverName(sqlServer.name())
                    .serverType(sqlServer.type())
                    .region(sqlServer.region().name())
                    .serverVersion(sqlServer.version())
                    .kind(sqlServer.kind())
                    .state(sqlServer.state())
                    .managedServiceIdentityEnabled(sqlServer.isManagedServiceIdentityEnabled())
                    .managedServiceIdentityType(sqlServer.managedServiceIdentityType().toString())
                    .publicNetworkAccess(sqlServer.publicNetworkAccess().toString())
                    .resourceGroupName(sqlServer.resourceGroupName())
                    .innerModelState(sqlServer.innerModel().state())
                    .administratorType(sqlServer.getActiveDirectoryAdministrator() != null
                            ? sqlServer.getActiveDirectoryAdministrator().administratorType().toString() : null)
                    .administratorSignInName(sqlServer.getActiveDirectoryAdministrator() != null
                            ? sqlServer.getActiveDirectoryAdministrator().signInName() : null)
                    .administratorId(sqlServer.getActiveDirectoryAdministrator() != null
                            ? sqlServer.getActiveDirectoryAdministrator().id() : null)
                    .privateEndpointConnectionIds(sqlServer.innerModel().privateEndpointConnections() != null
                            ? sqlServer.innerModel().privateEndpointConnections().stream()
                            .map(ServerPrivateEndpointConnection::id)
                            .collect(Collectors.toList()) : null)
                    .privateEndpointIds(sqlServer.innerModel().privateEndpointConnections() != null
                            ? sqlServer.innerModel().privateEndpointConnections().stream()
                            .map(connection -> connection.properties().privateEndpoint().id())
                            .collect(Collectors.toList()) : null)
                    .databases(sqlServer.databases().list().stream()
                            .map(sqlDatabase -> DatabaseDTO.builder()
                                    .databaseId(sqlDatabase.databaseId())
                                    .databaseName(sqlDatabase.name())
                                    .databaseType(sqlDatabase.innerModel().type())
                                    .status(sqlDatabase.status().toString())
                                    .dbStatus(sqlDatabase.innerModel().status().toString())
                                    .edition(sqlDatabase.edition().toString())
                                    .maxSizeBytes(sqlDatabase.maxSizeBytes())
                                    .region(sqlDatabase.region().toString())
                                    .readScale(sqlDatabase.innerModel().readScale().toString())
                                    .minCapacity(sqlDatabase.innerModel().minCapacity())
                                    .pausedDate(sqlDatabase.innerModel().pausedDate())
                                    .resumedDate(sqlDatabase.innerModel().resumedDate())
                                    .build())
                            .collect(Collectors.toList()))
                    .build();

            response.add(serverDTO);
        });
        return response;
    }


    public List<SubscriptionDTO> listSubscriptions() {
        List<SubscriptionDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId);
        PagedIterable<Subscription> subscriptions = azureResourceManager.subscriptions().list();
        subscriptions.forEach(subscription -> {
            SubscriptionDTO subscriptionDTO = SubscriptionDTO.builder()
                    .subscriptionId(subscription.subscriptionId())
                    .subscriptionName(subscription.displayName())
                    .subscriptionState(subscription.state().name())
                    .spendingLimit(subscription.subscriptionPolicies().spendingLimit().name())
                    .build();
            response.add(subscriptionDTO);
        });
        return response;
    }


    private boolean isCustomRole(String roleType) {
        return !Objects.equals(roleType, "BuiltInRole");
    }
}

