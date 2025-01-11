package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.azure.resourcemanager.authorization.models.RoleDefinition;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.resources.models.Subscription;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureAdIntegration.util.AzureEntityUtil;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.azureResourcesIntegration.entities.*;
import com.ws.azureResourcesIntegration.repository.*;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceSyncService {
    String wsTenantName;
    final String tenantEmail = "dummy@gmail.com";
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

    public void syncAzureResourceData(AzureTenant azureTenant, AzureUserCredentialDTO azureUserCredentialDTO) {
        try {
            this.wsTenantName = azureUserCredentialDTO.getWsTenantName();
            this.azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialDTO);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_DATA_SYNC_START, "Info");
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_DATA_TRUNCATED, "Info");
            log.info("0");
            truncateAzureResourcesDataThroughAzureTenant(azureTenant);
            log.info("0.1");
            AzureSubscription azureSubscription = syncSubscription(azureTenant);
            log.info("1");
            syncResourceGroups(azureTenant, azureSubscription);
            log.info("2");
            syncAzureVMs(azureTenant, azureSubscription);
            syncStorageData(azureTenant, azureSubscription);
            log.info("3");
            syncServersAndDatabases(azureTenant, azureSubscription);
            syncRoleDefinitions(azureTenant, azureSubscription);
            syncRoleAssignments(azureTenant, azureSubscription);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_DATA_SYNC_END, "Info");
        } catch (Exception ex) {
            log.error("Error occurred in syncing data from Azure Resources");
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.wsTenantName, Constant.AZURE_SYNC_FAILURE, ex.getMessage(), "Error");
            throw new RuntimeException(ex.getMessage());
        }
    }

    /* Source parent for all azure resource models like AzureVM, Storages => AzureSubscription */
    private void truncateAzureResourcesDataThroughAzureTenant(AzureTenant azureTenant) {
        azureSubscriptionRepository.deleteByAzureTenant(azureTenant);
    }


    /**
     * First Azure subscription needed to be fetched
     */
    private AzureSubscription syncSubscription(AzureTenant azureTenant) {
        AzureSubscription azureSubscription = null;
        try {
            Subscription subscription = this.azureResourceManager.subscriptions().getById(this.azureResourceManager.subscriptionId());
            azureSubscription = azureSubscriptionRepository.save(AzureSubscription.builder()
                    .azureSubscriptionId(subscription.subscriptionId())
                    .subscriptionName(subscription.displayName())
                    .subscriptionState(GenericUtil.getOrNull(() -> subscription.state().name()))
                    .spendingLimit(GenericUtil.getOrNull(() -> subscription.subscriptionPolicies().spendingLimit().name()))
                    .authorizationSource(GenericUtil.getOrNull(() -> subscription.innerModel().authorizationSource()))
                    .tags(GenericUtil.getOrNull(() -> subscription.innerModel().tags()))
                    .syncedAt(new Date())
                    .wsTenantName(this.wsTenantName)
                    .azureTenant(azureTenant)
                    .build());
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_SUBSCRIPTION_SYNCED, "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", ResourceGroup.class.getName(), ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + ResourceGroup.class.getName()), "Info");
        }
        if (ObjectUtils.isEmpty(azureSubscription)) {
            throw new RuntimeException("Failed to sync azure subscription data hence aborting whole sync process....");
        }
        return azureSubscription;
    }


    private void syncResourceGroups(AzureTenant azureTenant, AzureSubscription azureSubscription) {
        try {
            PagedIterable<ResourceGroup> resourceGroups = this.azureResourceManager.resourceGroups().list();
            List<AzureResourceGroup> azureResourceGroups = resourceGroups.stream()
                    .map(resourceGroup -> AzureResourceGroup.builder()
                            .azureResourceId(resourceGroup.id())
                            .name(resourceGroup.name())
                            .regionName(resourceGroup.regionName())
                            .syncedAt(new Date())
                            .tags(resourceGroup.tags())
                            .location(GenericUtil.getOrNull(() -> resourceGroup.innerModel().location()))
                            .wsTenantName(this.wsTenantName)
                            .azureSubscription(azureSubscription)
                            .build()
                    )
                    .collect(Collectors.toList());
            azureResourceGroupRepository.saveAll(azureResourceGroups);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_GROUPS_SYNCED, "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", ResourceGroup.class.getName(), ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + ResourceGroup.class.getName()), "Info");
        }
    }


    private void syncAzureVMs(AzureTenant azureTenant, AzureSubscription azureSubscription) {
        try {
            log.info("v.1");
            List<AzureVM> azureVMs = this.azureResourceManager.virtualMachines().list().stream()
                    .map(vm -> AzureVM.builder()
                            .azureVmId(vm.vmId())
                            .instanceId(vm.id())
                            .name(vm.name())
                            .computerName(vm.computerName())
                            .powerState(GenericUtil.getOrNull(() -> vm.powerState().toString()))
                            .size(GenericUtil.getOrNull(() -> vm.size().getValue()))
                            .osType(GenericUtil.getOrNull(() -> vm.osType().toString()))
                            .publicIpInstanceId(vm.getPrimaryPublicIPAddressId())
                            .resourceGroupName(vm.resourceGroupName())
                            .osDiskSize(vm.osDiskSize())
                            .region(vm.region().name())
                            .securityType(GenericUtil.getOrNull(() -> vm.securityType().toString()))
                            .resourceType(vm.type())
                            .resourceIdentityType(GenericUtil.getOrNull(() -> vm.innerModel().identity().type().name()))
                            .ipAddress(GenericUtil.getOrNull(() -> vm.getPrimaryPublicIPAddress().ipAddress()))
                            .syncedAt(new Date())
                            .azureSubscription(azureSubscription)
                            .wsTenantName(wsTenantName)
                            .build())
                    .collect(Collectors.toList());
            log.info("v.2");

            azureVMRepository.saveAll(azureVMs);
            log.info("v.3");

            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_VMS_SYNCED, "Info");
            log.info("v.4");

        } catch (Exception ignored) {
            log.info("v -> Error");
            log.error(String.format("Error in syncing %s with message: %s", VirtualMachine.class.getName(), ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + VirtualMachine.class.getName()), "Info");
        }
    }


    private void syncStorageData(AzureTenant azureTenant, AzureSubscription azureSubscription) {
        try {
            List<AzureStorageAccount> azureStorageAccounts = this.azureResourceManager.storageAccounts().list().stream().map(storageAccount -> AzureStorageAccount.builder()
                            .azureStorageAccountId(storageAccount.id())
                            .storageAccountName(storageAccount.name())
                            .region(GenericUtil.getOrNull(() -> storageAccount.region().toString()))
                            .createdDate(storageAccount.creationTime())
                            .kind(storageAccount.kind().toString())
                            .customDomainName(GenericUtil.getOrNull(() -> storageAccount.customDomain().name()))
                            .blobPublicAccessAllowed(storageAccount.isBlobPublicAccessAllowed())
                            .sharedKeyAccessAllowed(storageAccount.isSharedKeyAccessAllowed())
                            .isAccessAllowedFromAllNetworks(storageAccount.isAccessAllowedFromAllNetworks())
                            .publicAccess(GenericUtil.getOrNull(() -> storageAccount.publicNetworkAccess().toString()))
                            .accessTier(GenericUtil.getOrNull(() -> storageAccount.accessTier().name()))
                            .skuTier(GenericUtil.getOrNull(() -> storageAccount.innerModel().sku().tier().name()))
                            .resourceType(storageAccount.type())
                            .tags(storageAccount.tags())
                            .azureSubscription(azureSubscription)
                            .wsTenantName(this.wsTenantName)
                            .syncedAt(new Date())
                            .build())
                    .collect(Collectors.toList());
            azureStorageRepository.saveAll(azureStorageAccounts);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_STORAGES_SYNCED, "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", AzureStorageAccount.class.getName(), ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + AzureStorageAccount.class.getName()), "Info");
        }
    }

    private void syncServersAndDatabases(AzureTenant azureTenant, AzureSubscription azureSubscription) {
        try {
            List<AzureServer> azureServers = this.azureResourceManager.sqlServers().list().stream()
                    .map(sqlServer -> {
                        List<AzureDatabase> azureDatabases = sqlServer.databases().list().stream()
                                .map(sqlDatabase -> AzureDatabase.builder()
                                        .azureDatabaseId(sqlDatabase.databaseId())
                                        .databaseName(sqlDatabase.name())
                                        .azureServerId(sqlServer.id())
                                        .databaseType(GenericUtil.getOrNull(() -> sqlDatabase.innerModel().type()))
                                        .status(GenericUtil.getOrNull(() -> sqlDatabase.status().toString()))
                                        .dbStatus(GenericUtil.getOrNull(() -> sqlDatabase.innerModel().status().toString()))
                                        .edition(GenericUtil.getOrNull(() -> sqlDatabase.edition().toString()))
                                        .maxSizeBytes(sqlDatabase.maxSizeBytes())
                                        .region(GenericUtil.getOrNull(() -> sqlDatabase.region().toString()))
                                        .readScale(GenericUtil.getOrNull(() -> sqlDatabase.innerModel().readScale().toString()))
                                        .minCapacity(GenericUtil.getOrNull(() -> sqlDatabase.innerModel().minCapacity()))
                                        .pausedDate(GenericUtil.getOrNull(() -> sqlDatabase.innerModel().pausedDate()))
                                        .resumedDate(GenericUtil.getOrNull(() -> sqlDatabase.innerModel().resumedDate()))
                                        .resourceType(GenericUtil.getOrNull(() -> sqlDatabase.innerModel().type()))
                                        .syncedAt(new Date())
                                        .wsTenantName(this.wsTenantName)
                                        .build())
                                .collect(Collectors.toList());
                        return AzureServer.builder()
                                .azureServerId(sqlServer.id())
                                .serverName(sqlServer.name())
                                .type(GenericUtil.getOrNull(sqlServer::type))
                                .region(GenericUtil.getOrNull(() -> sqlServer.region().name()))
                                .serverVersion(sqlServer.version())
                                .kind(sqlServer.kind())
                                .state(sqlServer.state())
                                .managedServiceIdentityEnabled(sqlServer.isManagedServiceIdentityEnabled())
                                .managedServiceIdentityType(GenericUtil.getOrNull(() -> sqlServer.managedServiceIdentityType().toString()))
                                .publicNetworkAccess(GenericUtil.getOrNull(() -> sqlServer.publicNetworkAccess().toString()))
                                .resourceGroupName(sqlServer.resourceGroupName())
                                .innerModelState(GenericUtil.getOrNull(() -> sqlServer.innerModel().state()))
                                .administratorType(GenericUtil.getOrNull(() -> sqlServer.getActiveDirectoryAdministrator().administratorType().toString()))
                                .administratorSignInName(GenericUtil.getOrNull(() -> sqlServer.getActiveDirectoryAdministrator().signInName()))
                                .administratorId(GenericUtil.getOrNull(() -> sqlServer.getActiveDirectoryAdministrator().id()))
                                .resourceType(sqlServer.type())
                                .syncedAt(new Date())
                                .azureSubscription(azureSubscription)
                                .wsTenantName(this.wsTenantName)
                                .location(GenericUtil.getOrNull(() -> sqlServer.innerModel().location()))
                                .administratorLogin(sqlServer.administratorLogin())
                                .azureDatabases(azureDatabases)
                                .build();
                    })
                    .collect(Collectors.toList());
            azureServerRepository.saveAll(azureServers);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_SERVER_DATABASES_SYNCED, "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", AzureServer.class.getName(), ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + AzureServer.class.getName()), "Info");
            throw new RuntimeException(ignored.getMessage());
        }
    }

    private void syncRoleDefinitions(AzureTenant azureTenant, AzureSubscription azureSubscription) {
        try {
            azureRoleDefinitionRepository.deleteAllByAzureTenant(azureTenant);

            /* FETCHING AT SUBSCRIPTION LEVEL (includes Roles inherited from top hierarchies + created specifically for Subscription as well) */
            PagedIterable<RoleDefinition> subsRoleDefinitionsPage = azureResourceManager.accessManagement().roleDefinitions().listByScope(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, azureResourceManager.subscriptionId()));
            Set<RoleDefinition> roleDefinitionSet = subsRoleDefinitionsPage.stream().collect(Collectors.toSet());

            /* FETCHING AT EACH RESOURCE-GROUP LEVEL ONLY */
            PagedIterable<ResourceGroup> resourceGroups = azureResourceManager.resourceGroups().list();
            resourceGroups.forEach((resourceGroup -> {
                PagedIterable<RoleDefinition> rgRoles = azureResourceManager.accessManagement().roleDefinitions().listByScope(String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, azureResourceManager.subscriptionId(), resourceGroup.name()));
                Set<RoleDefinition> rgRoleDefinitionSet = rgRoles.stream()
                        .filter(rgRole -> rgRole.innerModel().roleType().equals("CustomRole") && rgRole.assignableScopes().contains(String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, azureResourceManager.subscriptionId(), resourceGroup.name())))
                        .collect(Collectors.toSet());
                roleDefinitionSet.addAll(rgRoleDefinitionSet);
            }));

            List<AzureRoleDefinition> azureRoleDefinitions = roleDefinitionSet.stream()
                    .filter(role -> !CollectionUtils.isEmpty(role.permissions()))
                    .map(role -> {
                        AzureRoleDefinition azureRoleDefinition = AzureRoleDefinition.builder()
                                .rolePathId(role.id())
                                .azureId(role.name())
                                .roleName(GenericUtil.getOrNull(() -> role.innerModel().roleName()))
                                .roleType(GenericUtil.getOrNull(() -> role.innerModel().roleType()))
                                .createdBy(GenericUtil.getOrNull(() -> role.innerModel().createdBy()))
                                .createdOn(GenericUtil.getOrNull(() -> role.innerModel().createdOn()))
                                .assignableScope(role.assignableScopes())
                                .syncedAt(new Date())
                                .azureSubscription(azureSubscription)
                                .wsTenantName(this.wsTenantName)
                                .azureTenant(azureTenant)
                                .build();

                        Set<AzureRoleDefinitionPermission> azurePermissions = role.permissions().stream()
                                .filter(permission -> !CollectionUtils.isEmpty(permission.actions()))
                                .map(permission -> {
                                    AzureRoleDefinitionPermission azurePermission = AzureRoleDefinitionPermission.builder()
                                            .permissionNameHash(null)
                                            .syncedAt(new Date())
                                            .wsTenantName(this.wsTenantName)
                                            .azureRoleDefinition(azureRoleDefinition)
                                            .build();

                                    List<AzureRoleDefinitionAction> roleDefinitionActions = new ArrayList<>(createAzureRoleDefinitionActions(permission.actions(), "ACTION", azurePermission, azureRoleDefinition, azureTenant));
                                    List<AzureRoleDefinitionAction> roleDefinitionNotActions = new ArrayList<>(createAzureRoleDefinitionActions(permission.notActions(), "NOT ACTION", azurePermission, azureRoleDefinition, azureTenant));
                                    roleDefinitionActions.addAll(roleDefinitionNotActions);
                                    azurePermission.setAzureRoleDefinitionActions(roleDefinitionActions);
                                    return azurePermission;
                                })
                                .collect(Collectors.toSet());

                        azureRoleDefinition.setAzureRoleDefinitionPermissions(azurePermissions);
                        return azureRoleDefinition;
                    })
                    .collect(Collectors.toList());

            azureRoleDefinitionRepository.saveAll(azureRoleDefinitions);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_SERVER_ROLE_DEFINITION_SYNCED, "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", RoleDefinition.class.getName(), ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + RoleDefinition.class.getName()), "Error");
        }
    }

    private List<AzureRoleDefinitionAction> createAzureRoleDefinitionActions(List<String> permissionActions, String type, AzureRoleDefinitionPermission azurePermission, AzureRoleDefinition azureRoleDefinition, AzureTenant azureTenant) {
        if (permissionActions == null || permissionActions.isEmpty()) {
            return Collections.emptyList();
        }
        return permissionActions.stream()
                .map(action -> AzureRoleDefinitionAction.builder()
                        .action(action)
                        .type(type)
                        .createdAt(new Date())
                        .subscriptionId(azureRoleDefinition.getAzureSubscription().getAzureSubscriptionId())
                        .wsTenantName(this.wsTenantName)
                        .azureRoleDefinitionPermission(azurePermission)
                        .azureRoleDefinition(azureRoleDefinition)
                        .build())
                .toList();
    }

    private void syncRoleAssignments(AzureTenant azureTenant, AzureSubscription azureSubscription) {
        try {
            azureRoleAssignmentRepository.deleteAllByAzureTenant(azureTenant);
            PagedIterable<RoleAssignment> roleAssignmentPage = this.azureResourceManager.accessManagement().roleAssignments().listByScope(String.format("/subscriptions/%s", this.azureResourceManager.subscriptionId()));
            List<AzureRoleAssignment> azureRoleAssignments = new ArrayList<>();
            for (RoleAssignment roleAssignment : roleAssignmentPage) {
                AzureRoleAssignment azureRoleAssignment = AzureEntityUtil.createAzureRoleAssignmentFromResourceEntity(roleAssignment,
                        AzureRoleAssignment.builder()
                                .azureSubscription(azureSubscription)
                                .wsTenantName(this.wsTenantName)
                                .azureTenant(azureTenant)
                                .build());
                azureRoleAssignments.add(azureRoleAssignment);
            }

            azureRoleAssignmentRepository.saveAll(azureRoleAssignments);
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", RoleDefinition.class.getName(), ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + RoleDefinition.class.getName()), "Error");
        }
    }

}