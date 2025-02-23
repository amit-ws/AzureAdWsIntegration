package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.azure.resourcemanager.authorization.models.RoleDefinition;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.containerservice.models.Code;
import com.azure.resourcemanager.containerservice.models.CredentialResult;
import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.resources.models.Subscription;
import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.exception.K8ResourceException;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureAdIntegration.util.AzureEntityUtil;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.azureKuberntesJIT.dto.ClusterConfigurationRequest;
import com.ws.azureKuberntesJIT.dto.K8ResourceDataSyncRequest;
import com.ws.azureKuberntesJIT.service.K8ResourcesDataService;
import com.ws.azureKuberntesJIT.service.K8ResourcesSyncService;
import com.ws.azureResourcesIntegration.constant.KubernetesClusterCredentialType;
import com.ws.azureResourcesIntegration.entities.*;
import com.ws.azureResourcesIntegration.repository.*;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
    final AzureKubernetesClusterRepository azureKubernetesClusterRepository;
    final AzureAuthUtil azureAuthUtil;
    final K8ResourcesSyncService k8ResourcesSyncService;
    final K8ResourcesDataService k8ResourcesDataService;

    @Autowired
    public AzureResourceSyncService(AzureSubscriptionRepository azureSubscriptionRepository, AzureResourceGroupRepository azureResourceGroupRepository, AzureServerRepository azureServerRepository, AzureRoleDefinitionRepository azureRoleDefinitionRepository, AzureRoleAssignmentRepository azureRoleAssignmentRepository, AzureVMRepository azureVMRepository, AzureStorageRepository azureStorageRepository, BackendApplicationLogservice backendApplicationLogservice, AzureKubernetesClusterRepository azureKubernetesClusterRepository, AzureAuthUtil azureAuthUtil, K8ResourcesSyncService k8ResourcesSyncService, K8ResourcesDataService k8ResourcesDataService) {
        this.azureSubscriptionRepository = azureSubscriptionRepository;
        this.azureResourceGroupRepository = azureResourceGroupRepository;
        this.azureServerRepository = azureServerRepository;
        this.azureRoleDefinitionRepository = azureRoleDefinitionRepository;
        this.azureRoleAssignmentRepository = azureRoleAssignmentRepository;
        this.azureVMRepository = azureVMRepository;
        this.azureStorageRepository = azureStorageRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureKubernetesClusterRepository = azureKubernetesClusterRepository;
        this.azureAuthUtil = azureAuthUtil;
        this.k8ResourcesSyncService = k8ResourcesSyncService;
        this.k8ResourcesDataService = k8ResourcesDataService;
    }

    public void syncAzureResourceData(AzureTenant azureTenant, AzureUserCredentialDTO azureUserCredentialDTO) {
        try {
            initializeWsTenantNameAndAzureResourceManager(azureUserCredentialDTO);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_DATA_SYNC_START, "Info");
            truncateAzureResourcesDataThroughAzureTenant(azureTenant);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_DATA_TRUNCATED, "Info");
            k8ResourcesDataService.deleteK8ResourcesByWsTenantName(this.wsTenantName);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.KUBERNETES_RESOURCES_DATA_TRUNCATED, "Info");
            AzureSubscription azureSubscription = syncSubscription(azureTenant);
            Map<String, AzureResourceGroup> azureResourceGroupMap = createAzureResourceGroupMap(syncResourceGroups(azureSubscription));
//            syncAzureVMs(azureSubscription, azureResourceGroupMap);
//            syncStorageData(azureSubscription, azureResourceGroupMap);
//            syncServersAndDatabases(azureSubscription, azureResourceGroupMap);
//            syncRoleDefinitions(azureTenant, azureSubscription);
//            syncRoleAssignments(azureTenant, azureSubscription);
            List<AzureKubernetesCluster> azureKubernetesClusters = syncAzureKubernetesClusters(azureSubscription, azureResourceGroupMap);
            syncKubernetesResources(azureSubscription.getAzureSubscriptionId(), azureKubernetesClusters);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_DATA_SYNC_END, "Info");
        } catch (Exception ex) {
            log.error("Error occurred in syncing data from Azure Resources");
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.wsTenantName, Constant.AZURE_SYNC_FAILURE, ex.getMessage(), "Error");
            throw new RuntimeException(ex.getMessage());
        }
    }


    public void syncAzureRoleAssignmentData(AzureTenant azureTenant, AzureUserCredentialDTO azureUserCredentialDTO) {
        initializeWsTenantNameAndAzureResourceManager(azureUserCredentialDTO);
        AzureSubscription azureSubscription = syncOrGetAzureSubscription(azureTenant);
        syncRoleAssignments(azureTenant, azureSubscription);
    }

    private void initializeWsTenantNameAndAzureResourceManager(AzureUserCredentialDTO azureUserCredentialDTO) {
        this.wsTenantName = azureUserCredentialDTO.getWsTenantName();
        this.azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialDTO);
    }

    private AzureSubscription syncOrGetAzureSubscription(AzureTenant azureTenant) {
        return Optional.ofNullable(azureTenant.getAzureSubscriptions())
                .filter(subscriptions -> !subscriptions.isEmpty())
                .map(subscriptions -> subscriptions.get(0))
                .orElseGet(() -> syncSubscription(azureTenant));
    }

    /* Source parent for all azure resource models like AzureVM, Storages => AzureSubscription */
    private void truncateAzureResourcesDataThroughAzureTenant(AzureTenant azureTenant) {
        azureSubscriptionRepository.deleteByAzureTenant(azureTenant);
    }

    private Map<String, AzureResourceGroup> createAzureResourceGroupMap(List<AzureResourceGroup> azureResourceGroups) {
        return azureResourceGroups.stream().collect(Collectors.toMap(
                azureResourceGroup -> azureResourceGroup.getName().toUpperCase(),
                azureResourceGroup -> azureResourceGroup));
    }

    private List<ClusterConfigurationRequest> createK8ClusterAndConfigTriples(List<AzureKubernetesCluster> azureKubernetesClusters) {
        return azureKubernetesClusters.stream()
                .map(azureCluster -> {
                    String severURL = EncryptionUtil.getDecryptedKey(azureCluster.getAzureK8ClusterCredentials().get(0).getClusterServerUrl(), Constant.AKS_CLUSTER_SERVER_URL);
                    String token = EncryptionUtil.getDecryptedKey(azureCluster.getAzureK8ClusterCredentials().get(0).getToken(), Constant.AKS_CLUSTER_TOKEN);
                    return ClusterConfigurationRequest.builder()
                            .clusterId(azureCluster.getAzureId())
                            .clusterName(azureCluster.getName())
                            .server(severURL)
                            .token(token)
                            .build();
                })
                .collect(Collectors.toList());
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


    private List<AzureResourceGroup> syncResourceGroups(AzureSubscription azureSubscription) {
        List<AzureResourceGroup> azureResourceGroups = null;
        try {
            PagedIterable<ResourceGroup> resourceGroups = this.azureResourceManager.resourceGroups().list();
            azureResourceGroups = azureResourceGroupRepository.saveAllAndFlush(resourceGroups.stream()
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
                    .collect(Collectors.toList()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_GROUPS_SYNCED, "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", ResourceGroup.class.getName(), ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + ResourceGroup.class.getName()), "Info");
        }

        if (CollectionUtils.isEmpty(azureResourceGroups)) {
            throw new RuntimeException("Failed to sync resource groups data");
        }
        return azureResourceGroups;
    }


    private void syncAzureVMs(AzureSubscription azureSubscription, Map<String, AzureResourceGroup> azureResourceGroupMap) {
        try {
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
                            .subscriptionId(azureSubscription.getAzureSubscriptionId())
                            .azureResourceGroup(azureResourceGroupMap.get(vm.resourceGroupName().toUpperCase()))
                            .azureSubscription(azureSubscription)
                            .wsTenantName(wsTenantName)
                            .build())
                    .collect(Collectors.toList());
            log.info("vm synced");
            azureVMRepository.saveAll(azureVMs);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_VMS_SYNCED, "Info");
        } catch (Exception ignored) {
            log.info("v -> Error");
            log.error(String.format("Error in syncing %s with message: %s", VirtualMachine.class.getName(), ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + VirtualMachine.class.getName()), "Info");
        }
    }


    private void syncStorageData(AzureSubscription azureSubscription, Map<String, AzureResourceGroup> azureResourceGroupMap) {
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
                            .resourceGroupName(storageAccount.resourceGroupName())
                            .tags(storageAccount.tags())
                            .subscriptionId(azureSubscription.getAzureSubscriptionId())
                            .azureResourceGroup(azureResourceGroupMap.get(storageAccount.resourceGroupName().toUpperCase()))
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

    private void syncServersAndDatabases(AzureSubscription azureSubscription, Map<String, AzureResourceGroup> azureResourceGroupMap) {
        try {
            List<AzureServer> azureServers = this.azureResourceManager.sqlServers().list().stream()
                    .map(sqlServer -> {
                        List<AzureDatabase> azureDatabases = sqlServer.databases().list().stream()
                                .map(sqlDatabase -> AzureDatabase.builder()
                                        .instanceId(sqlDatabase.id())
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
                                        .resourceGroupName(sqlDatabase.resourceGroupName())
                                        .subscriptionId(azureSubscription.getAzureSubscriptionId())
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
                                .subscriptionId(azureSubscription.getAzureSubscriptionId())
                                .azureResourceGroup(azureResourceGroupMap.get(sqlServer.resourceGroupName().toUpperCase()))
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
            PagedIterable<RoleAssignment> roleAssignmentPage = this.azureResourceManager.accessManagement().roleAssignments().listByScope(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, this.azureResourceManager.subscriptionId()));
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
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_SERVER_ROLE_ASSIGNMENT_SYNCED, "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", RoleDefinition.class.getName(), ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + RoleDefinition.class.getName()), "Error");
        }
    }


    private List<AzureKubernetesCluster> syncAzureKubernetesClusters(AzureSubscription azureSubscription, Map<String, AzureResourceGroup> azureResourceGroupMap) {
        List<AzureKubernetesCluster> savedKubernetesClusters = new ArrayList<>();
        try {
            PagedIterable<KubernetesCluster> kubernetesClusters = azureResourceManager.kubernetesClusters().list();
            List<AzureKubernetesCluster> azureKubernetesClusters = kubernetesClusters.stream()
                    .map(kubernetesCluster -> {
                        AzureKubernetesCluster azureKubernetesCluster = AzureKubernetesCluster.builder()
                                .azureId(kubernetesCluster.id())
                                .name(kubernetesCluster.name())
                                .powerState(GenericUtil.getOrNull(() -> kubernetesCluster.powerState().code().getValue()))
                                .resourceGroupName(kubernetesCluster.resourceGroupName())
                                .regionName(kubernetesCluster.regionName())
                                .publicNetworkAccess(GenericUtil.getOrNull(() -> kubernetesCluster.publicNetworkAccess().getValue()))
                                .nodeResourceGroup(kubernetesCluster.nodeResourceGroup())
                                .isLocalAccountsEnabled(String.valueOf(kubernetesCluster.isLocalAccountsEnabled()))
                                .managedClusterIdentityType(GenericUtil.getOrNull(() -> kubernetesCluster.innerModel().identity().type().name()))
                                .kubernetesVersion(kubernetesCluster.version())
                                .isAzureRbacEnabled(kubernetesCluster.isAzureRbacEnabled())
                                .type(kubernetesCluster.type())
                                .subscriptionId(azureSubscription.getAzureSubscriptionId())
                                .azureResourceGroup(azureResourceGroupMap.get(kubernetesCluster.resourceGroupName().toUpperCase()))
                                .azureSubscription(azureSubscription)
                                .wsTenantName(this.wsTenantName)
                                .build();

                        azureKubernetesCluster.setAzureK8ClusterCredentials(createK8ClusterCredentials(azureKubernetesCluster, kubernetesCluster.adminKubeConfigs(), kubernetesCluster.resourceGroupName(), azureSubscription.getAzureSubscriptionId(), KubernetesClusterCredentialType.ADMIN));
                        return azureKubernetesCluster;
                    })
                    .toList();

            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_KUBERNETES_CLUSTERS_SYNCED, "Info");
            savedKubernetesClusters = azureKubernetesClusterRepository.saveAll(azureKubernetesClusters);
        } catch (Exception e) {
            log.error(String.format("Error in syncing %s with message: %s", KubernetesCluster.class.getName(), e.getMessage()));
            if (e.getMessage().contains("403")) {
                backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.INSUFFICIENT_PRIVILEGEE_FOR_AKS_ADMIN_CONFIG_FETCH, "Error");
            }
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, (Constant.ERROR_IN_SYNCING_AZURE_RESOURCES + KubernetesCluster.class.getName()), "Error");
        }

        return savedKubernetesClusters;
    }


    private List<AzureK8ClusterCredential> createK8ClusterCredentials(AzureKubernetesCluster cluster, List<CredentialResult> credentialResults,
                                                                      String resourceGroupName, String subscriptionId, KubernetesClusterCredentialType type) {
        return credentialResults.stream()
                .map(credentialResult -> {
                    Map<String, String> configMap = GenericUtil.extractServerAndTokenFromKubeConfigYAML(new String(credentialResult.value()));
                    return AzureK8ClusterCredential.builder()
                            .name(credentialResult.name())
                            .clusterServerUrl(EncryptionUtil.getEncryptedKey(configMap.get("server"), Constant.AKS_CLUSTER_SERVER_URL))
                            .token(EncryptionUtil.getEncryptedKey(configMap.get("token"), Constant.AKS_CLUSTER_TOKEN))
                            .type(type)
                            .azureKubernetesCluster(cluster)
                            .resourceGroupName(resourceGroupName)
                            .subscriptionId(subscriptionId)
                            .syncedAt(new Date())
                            .build();
                })
                .collect(Collectors.toList());
    }

    public void syncK8ResourcesData(AzureTenant azureTenant, AzureUserCredentialDTO azureUserCredentialDTO) {
        List<AzureKubernetesCluster> azureKubernetesClusters = azureKubernetesClusterRepository.findAllByWsTenantNameAndPowerState(azureUserCredentialDTO.getWsTenantName(), Code.RUNNING.getValue());
        if (CollectionUtils.isEmpty(azureKubernetesClusters)) {
            throw new K8ResourceException("No AKS found for provided tenant: " + wsTenantName);
        }
        initializeWsTenantNameAndAzureResourceManager(azureUserCredentialDTO);
        AzureSubscription azureSubscription = syncOrGetAzureSubscription(azureTenant);
        k8ResourcesDataService.deleteK8ResourcesByWsTenantName(this.wsTenantName);
        syncKubernetesResources(azureSubscription.getAzureSubscriptionId(), azureKubernetesClusters);
    }


    /* SYNC Azure Clusters resources (Kubernetes resources for AKS) */
    private void syncKubernetesResources(String subscriptionId, List<AzureKubernetesCluster> azureKubernetesClusters) {
        List<ClusterConfigurationRequest> credentialConfigs = createK8ClusterAndConfigTriples(azureKubernetesClusters);
        if (CollectionUtils.isEmpty(credentialConfigs)) {
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.KUBERNETES_RESOURCES_DATA_SYNC_SKIPPED, "Info");
            return;
        }
        K8ResourceDataSyncRequest k8ResourceDataSyncRequest = K8ResourceDataSyncRequest.builder()
                .configurations(credentialConfigs)
                .resourceAccountId(subscriptionId)
                .cloudProviderType(CloudProviderType.AZURE)
                .wsTenantName(this.wsTenantName)
                .tenantEmail(this.tenantEmail)
                .build();
        try {
            k8ResourcesSyncService.syncKubernetesData(k8ResourceDataSyncRequest);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.KUBERNETES_RESOURCES_SYNCED, "Info");
        } catch (Exception ignored) {
            log.info("Inside AzureResourceSyncService at -> syncKubernetesResources");
            log.warn(String.format("Error in syncing Kubernetes resources with message %s:", ignored.getMessage()));
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.ERROR_IN_SYNCING_KUBERNETES_DATA, "Error");
        }
    }

}