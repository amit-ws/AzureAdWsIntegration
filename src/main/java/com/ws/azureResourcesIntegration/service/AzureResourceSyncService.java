package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.resources.models.Subscription;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureResourcesIntegration.entities.*;
import com.ws.azureResourcesIntegration.repository.*;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceSyncService {
    String wsTenantName;
    String tenantEmail = "dummy@gmail.com";
    AzureResourceManager azureResourceManager;
    final AzureSubscriptionRepository azureSubscriptionRepository;
    final AzureResourceGroupRepository azureResourceGroupRepository;
    final AzureServerRepository azureServerRepository;
    final AzureRoleDefinitionRepository azureRoleDefinitionRepository;
    final AzureRoleAssignmentRepository azureRoleAssignmentRepository;
    final AzureVMRepository azureVMRepository;
    final AzureStorageRepository azureStorageRepository;
    final BackendApplicationLogservice backendApplicationLogservice;
    final AzureAuthUtil azureAuthUtil;

    @Autowired
    public AzureResourceSyncService(AzureSubscriptionRepository azureSubscriptionRepository, AzureResourceGroupRepository azureResourceGroupRepository, AzureServerRepository azureServerRepository, AzureRoleDefinitionRepository azureRoleDefinitionRepository, AzureRoleAssignmentRepository azureRoleAssignmentRepository, AzureVMRepository azureVMRepository, AzureStorageRepository azureStorageRepository, BackendApplicationLogservice backendApplicationLogservice, AzureAuthUtil azureAuthUtil) {
        this.azureSubscriptionRepository = azureSubscriptionRepository;
        this.azureResourceGroupRepository = azureResourceGroupRepository;
        this.azureServerRepository = azureServerRepository;
        this.azureRoleDefinitionRepository = azureRoleDefinitionRepository;
        this.azureRoleAssignmentRepository = azureRoleAssignmentRepository;
        this.azureVMRepository = azureVMRepository;
        this.azureStorageRepository = azureStorageRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureAuthUtil = azureAuthUtil;
    }

    public void syncAzureResourceData(AzureTenant azureTenant, AzureUserCredential azureUserCredential) {
        try {
            this.wsTenantName = azureUserCredential.getWsTenantName();
            this.azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredential);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_DATA_SYNC_START, "Info");
            syncAzureVMs(azureTenant);
            syncSubscriptions(azureTenant);
            syncResourceGroups(azureTenant);
            syncStorageData(azureTenant);
            syncServersAndDatabases(azureTenant);
            /* sync below data's too
             * azure roles
             * azure role assignments
             * */
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_DATA_SYNC_END, "Info");
        } catch (Exception ex) {
            log.error("Error occurred in syncing data from Azure Resources");
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.wsTenantName, Constant.AZURE_SYNC_FAILURE, ex.getMessage(), "Error");
            throw new RuntimeException(ex.getMessage());
        }
    }


    private void syncAzureVMs(AzureTenant azureTenant) {
        /* Delete all VMs for this azureTenant and re-create the new ones */
        azureVMRepository.deleteAllByAzureTenant(azureTenant);
        try {
            List<AzureVM> azureVMs = this.azureResourceManager.virtualMachines().list().stream()
                    .map(vm -> AzureVM.builder()
                            .azureVmId(vm.vmId())
                            .instanceId(vm.id())
                            .name(vm.name())
                            .computerName(vm.computerName())
                            .powerState(vm.powerState().toString())
                            .size(vm.size().getValue())
                            .osType(vm.osType().toString())
                            .publicIpInstanceId(vm.getPrimaryPublicIPAddressId())
                            .resourceGroupName(vm.resourceGroupName())
                            .osDiskSize(vm.osDiskSize())
                            .region(vm.region().name())
                            .securityType(vm.securityType().toString())
                            .type(vm.type())
                            .resourceIdentityType(vm.innerModel().identity() != null ? vm.innerModel().identity().type().name() : null)
                            .ipAddress(vm.getPrimaryPublicIPAddress() != null ? vm.getPrimaryPublicIPAddress().ipAddress() : null)
                            .syncedAt(new Date())
                            .azureTenant(azureTenant)
                            .wsTenantName(wsTenantName)
                            .build())
                    .collect(Collectors.toList());
            azureVMRepository.saveAll(azureVMs);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_VMS_SYNCED, "Info");
        } catch (Exception ignored) {
            if (ignored.getMessage().contains(String.valueOf(HttpStatus.FORBIDDEN.value()))) {
                backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + VirtualMachine.class.getName()), "Info");
            }
        }
    }


    private void syncSubscriptions(AzureTenant azureTenant) {
        /* Delete all Subscription details for this azureTenant and re-create the new ones */
        azureSubscriptionRepository.deleteAllByAzureTenant(azureTenant);
        try {
            PagedIterable<Subscription> subscriptions = this.azureResourceManager.subscriptions().list();
            List<AzureSubscription> azureSubscriptions =
                    StreamSupport.stream(subscriptions.spliterator(), false)
                            .map(subscription -> AzureSubscription.builder()
                                    .azureSubscriptionId(subscription.subscriptionId())
                                    .subscriptionName(subscription.displayName())
                                    .subscriptionState(subscription.state().name())
                                    .spendingLimit(subscription.subscriptionPolicies().spendingLimit().name())
                                    .syncedAt(new Date())
                                    .wsTenantName(this.wsTenantName)
                                    .azureTenant(azureTenant)
                                    .build()
                            )
                            .toList();
            azureSubscriptionRepository.saveAll(azureSubscriptions);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_SUBSCRIPTION_SYNCED, "Info");
        } catch (Exception ignored) {
            if (ignored.getMessage().contains(String.valueOf(HttpStatus.FORBIDDEN.value()))) {
                backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + Subscription.class.getName()), "Info");
            }
        }
    }

    private void syncResourceGroups(AzureTenant azureTenant) {
        /* Delete all existing Resources groups details for this azureTenant and re-create the new ones */
        azureResourceGroupRepository.deleteAllByAzureTenant(azureTenant);
        try {
            PagedIterable<ResourceGroup> resourceGroups = this.azureResourceManager.resourceGroups().list();
            List<AzureResourceGroup> azureResourceGroups = resourceGroups.stream()
                    .map(resourceGroup -> AzureResourceGroup.builder()
                            .azureResourceId(resourceGroup.id())
                            .name(resourceGroup.name())
                            .regionName(resourceGroup.regionName())
                            .syncedAt(new Date())
                            .wsTenantName(this.wsTenantName)
                            .azureTenant(azureTenant)
                            .build()
                    )
                    .collect(Collectors.toList());
            azureResourceGroupRepository.saveAll(azureResourceGroups);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_GROUPS_SYNCED, "Info");
        } catch (Exception ignored) {
            if (ignored.getMessage().contains(String.valueOf(HttpStatus.FORBIDDEN.value()))) {
                backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + ResourceGroup.class.getName()), "Info");
            }
        }
    }


    private void syncStorageData(AzureTenant azureTenant) {
        /* Delete all existing Storage details for this azureTenant and re-create the new ones */
        azureStorageRepository.deleteAllByAzureTenant(azureTenant);
        try {
            List<AzureStorage> azureStorages = this.azureResourceManager.storageAccounts().list().stream()
                    .flatMap(storageAccount -> this.azureResourceManager.storageBlobContainers()
                            .list(storageAccount.resourceGroupName(), storageAccount.name()).stream()
                            .map(container -> AzureStorage.builder()
                                    .azureStorageAccountId(storageAccount.id())
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
                                    .containerType(container.type() != null ? container.type().toString() : null)
                                    .wsTenantName(this.wsTenantName)
                                    .azureTenant(azureTenant)
                                    .syncedAt(new Date())
                                    .build())
                    )
                    .toList();
            azureStorageRepository.saveAll(azureStorages);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_STORAGES_SYNCED, "Info");
        } catch (Exception ignored) {
            if (ignored.getMessage().contains(String.valueOf(HttpStatus.FORBIDDEN.value()))) {
                backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + AzureStorage.class.getName()), "Info");
            }
        }
    }

    private void syncServersAndDatabases(AzureTenant azureTenant) {
        /* Delete all Servers and associated databases details for this azureTenant and re-create the new ones */
        azureServerRepository.deleteAllByAzureTenant(azureTenant);
        try {
            List<AzureServer> azureServers = this.azureResourceManager.sqlServers().list().stream()
                    .map(sqlServer -> {
                        List<AzureDatabase> azureDatabases = sqlServer.databases().list().stream()
                                .map(sqlDatabase -> AzureDatabase.builder()
                                        .azureDatabaseId(sqlDatabase.databaseId())
                                        .databaseName(sqlDatabase.name())
                                        .azureServerId(sqlServer.id())
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
                                        .syncedAt(new Date())
                                        .wsTenantName(this.wsTenantName)
                                        .azureTenant(azureTenant)
                                        .build())
                                .collect(Collectors.toList());
                        return AzureServer.builder()
                                .azureServerId(sqlServer.id())
                                .serverName(sqlServer.name())
                                .type(sqlServer.type())
                                .region(sqlServer.region().name())
                                .serverVersion(sqlServer.version())
                                .kind(sqlServer.kind())
                                .state(sqlServer.state())
                                .managedServiceIdentityEnabled(sqlServer.isManagedServiceIdentityEnabled())
                                .managedServiceIdentityType(sqlServer.managedServiceIdentityType().toString())
                                .publicNetworkAccess(sqlServer.publicNetworkAccess().toString())
                                .resourceGroupName(sqlServer.resourceGroupName())
                                .innerModelState(sqlServer.innerModel().state())
                                .administratorType(sqlServer.getActiveDirectoryAdministrator() != null ? sqlServer.getActiveDirectoryAdministrator().administratorType().toString() : null)
                                .administratorSignInName(sqlServer.getActiveDirectoryAdministrator() != null ? sqlServer.getActiveDirectoryAdministrator().signInName() : null)
                                .administratorId(sqlServer.getActiveDirectoryAdministrator() != null ? sqlServer.getActiveDirectoryAdministrator().id() : null)
                                .syncedAt(new Date())
                                .wsTenantName(this.wsTenantName)
                                .azureTenant(azureTenant)
                                .location(sqlServer.innerModel().location())
                                .administratorLogin(sqlServer.administratorLogin())
                                .azureDatabases(azureDatabases)
                                .build();
                    })
                    .toList();
            azureServerRepository.saveAll(azureServers);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_SERVER_DATABASES_SYNCED, "Info");
        } catch (Exception ignored) {
            if (ignored.getMessage().contains(String.valueOf(HttpStatus.FORBIDDEN.value()))) {
                backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + AzureServer.class.getName()), "Info");
            }
        }
    }

}
