package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.azure.resourcemanager.authorization.models.RoleDefinition;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.repository.AzureUserGroupMembershipRepository;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureResourcesIntegration.configuration.AzureResourceAuthFactory;
import com.ws.azureResourcesIntegration.dto.*;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceFeatureService {
    @Value("${spring.cloud.azure.active-directory.client-id}")
    String clientId;

    @Value("${spring.cloud.azure.active-directory.client-secret}")
    String clientSecret;

    @Value("${spring.cloud.azure.active-directory.tenant-id}")
    String tenantId;

    @Value("${spring.cloud.azure.active-directory.subscription-id}")
    String subscriptionId;
    final AzureResourceAuthFactory azureResourceAuthFactory;
    final AzureUserRepository azureUserRepository;
    final AzureUserGroupMembershipRepository azureUserGroupMembershipRepository;
    final AzureUserCredentialRepository azureUserCredentialRepository;


    @Autowired
    public AzureResourceFeatureService(AzureResourceAuthFactory azureResourceAuthFactory, AzureUserRepository azureUserRepository, AzureUserGroupMembershipRepository azureUserGroupMembershipRepository, AzureUserCredentialRepository azureUserCredentialRepository) {
        this.azureResourceAuthFactory = azureResourceAuthFactory;
        this.azureUserRepository = azureUserRepository;
        this.azureUserGroupMembershipRepository = azureUserGroupMembershipRepository;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
    }

    private AzureResourceManager getAzureResourceManager() {
        return azureResourceAuthFactory.createResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }

    private AzureResourceManager getAzureResourceManager(String clientId, String clientSecret, String tenantId, String subscriptionId) {
        return azureResourceAuthFactory.createResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }


    /**
     * Feature: Assign a particular resource to a user/group for certain time period
     *
     * @param principleId       -> The target user/group
     * @param resourceGroupName -> Container holding the resources
     * @param resourceId        -> Specific resource like Vm, Db etc
     * @param expirationTime    -> exact time
     */
    public void assignResourceToPrincipleForCertainTimePeriod(String principleId, String resourceGroupName, String resourceId, OffsetDateTime expirationTime) {
        AzureResourceManager azureResourceManager = getAzureResourceManager();

        // 0. Prepare the String for the target resource which is supposed to be used (assigned to the principle for some time)
        String resource = String.format("/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Compute/virtualMachines/", azureResourceManager.subscriptionId(), resourceGroupName, resourceId);

        // 1. Find the Reader role definition
        RoleDefinition readerRoleDefinition = azureResourceManager.accessManagement()
                .roleDefinitions()
                .listByScope("/subscriptions/" + azureResourceManager.subscriptionId())
                .stream()
                .filter(role -> role.roleName().equalsIgnoreCase("Reader"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Reader role not found"));

        // 2. Assign this role to the target principle
        RoleAssignment roleAssignment = azureResourceManager.accessManagement()
                .roleAssignments()
                .define(UUID.randomUUID().toString())
                .forObjectId(principleId)
                .withRoleDefinition(readerRoleDefinition.id())
                .withScope(resource)
                .create();

        // 3. Store the time in local DB about the mapping
        // 4. Use CRON Job to remove the access and then remove the mapping row from the db
        removeAccessOfResourceFromPrinciple(principleId, readerRoleDefinition.id());
    }

    public void removeAccessOfResourceFromPrinciple(String principleId, String roleDefinitionId) {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        String roleAssingmentId = ""; // Fetch it from the DB
        azureResourceManager.accessManagement().roleAssignments().deleteById(roleAssingmentId);
    }


    /**
     * Feature: List down all the Permissions a user/groupd i.e principle has
     *
     * @param principleId
     * @return
     */
    public Set<String> getAllPermissionsForPrinciple(String principleId) {
        Set<String> permissions = new HashSet<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager();

        // 1. List down al the RoleAssignment for this principle
        // 2. For each of the Assignment get the RoleDefinition (role)
        // 3. Get what set of permissions does each of the RoleDefinitions has and then add in the result list
        PagedIterable<RoleAssignment> roleAssignments = azureResourceManager.accessManagement().roleAssignments().listByServicePrincipal(principleId);
        for (RoleAssignment assignment : roleAssignments) {
            RoleDefinition roleDefinition = azureResourceManager.accessManagement().roleDefinitions().getById(assignment.roleDefinitionId());
            if (roleDefinition != null) {
                permissions.addAll(roleDefinition.permissions().stream()
                        .flatMap(permission -> permission.actions().stream())
                        .toList());
            }
        }
        return permissions;
    }


    /**
     * Feature: Assign a Role to a User or Group i.e Principle
     *
     * @param roleDefinitionId -> Role id
     * @param principleId      -> target users/groups
     */
    public void assignRoleToUserOrGroup(String roleDefinitionId, String principleId) {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<RoleAssignment> assignments = azureResourceManager.accessManagement().roleAssignments().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        Optional<RoleAssignment> assignment = assignments.stream()
                .filter(a -> a.roleDefinitionId().equalsIgnoreCase(roleDefinitionId))
                .findFirst();

        if (assignment.isEmpty()) {
            throw new RuntimeException("No role found with provided role definition id: " + roleDefinitionId);
        }

        azureResourceManager.accessManagement()
                .roleAssignments()
                .define(assignment.get().id())
                .forObjectId(principleId)
                .withRoleDefinition(roleDefinitionId)
                .withScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()))
                .create();
    }


    /**
     * Feature: List of Users with their respective details, groups, roles and permissions
     *
     * @param tenantName
     * @return
     */
    public List<UserGroupRolePermissionResponse> usersWithGroupsRolesPermissions(String tenantName) {
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findByWsTenantName(tenantName)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided tenant: " + tenantName));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        List<UserGroupRolePermissionResponse> response = new ArrayList<>();
        List<String> userIds = azureUserRepository.findAllByWsTenantName(tenantName)
                .stream()
                .map(AzureUser::getAzureId)
                .collect(Collectors.toList());
        Map<String, UserIdGroup> userIdGroupMap = azureUserGroupMembershipRepository.fetchGroupsForUsers(userIds)
                .stream()
                .collect(Collectors.toMap(UserIdGroup::getUserId, azureGroup -> azureGroup));
        userIds.forEach(principleId ->
                azureResourceManager.accessManagement().roleAssignments().listByServicePrincipal(principleId)
                        .forEach(assignment -> {
                            RoleDefinition roleDefinition = azureResourceManager.accessManagement().roleDefinitions().getById(assignment.roleDefinitionId());
                            UserIdGroup userIdGroup = userIdGroupMap.get(principleId);
                            Set<String> permissions = roleDefinition.permissions().stream()
                                    .flatMap(permission -> permission.actions().stream())
                                    .collect(Collectors.toSet());
                            UserGroupRolePermissionResponse user = UserGroupRolePermissionResponse
                                    .builder()
                                    .userId(principleId)
                                    .displayName(userIdGroup.getDisplayName())
                                    .groupName(userIdGroup.getGroupName())
                                    .role(roleDefinition.name())
                                    .permissions(permissions)
                                    .build();
                            response.add(user);
                        })
        );
        return response;
    }


    /**
     * Feature: List all VMS details for the tenant
     *
     * @param tenantName
     */
    public PagedIterable<VirtualMachine> listAllVmsForTenant(String tenantName) {
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findByWsTenantName(tenantName)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided tenant: " + tenantName));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        return azureResourceManager.virtualMachines().list();
    }


    /**
     * Feature: List all storage details for the tenant
     *
     * @param tenantName
     * @return
     */
    public List<StorageAccountDTO> listAllStorageDetailsForTenant(String tenantName) {
        List<StorageAccountDTO> response = new ArrayList<>();
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findByWsTenantName(tenantName)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided tenant: " + tenantName));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        azureResourceManager.storageAccounts().list().forEach(storageAccount ->
                azureResourceManager.storageBlobContainers()
                        .list(storageAccount.resourceGroupName(), storageAccount.name())
                        .forEach(container -> {
                            StorageAccountDTO storageDetails = StorageAccountDTO.builder()
                                    .storageAccountId(storageAccount.id())
                                    .storageAccountName(storageAccount.name())
                                    .storageAccountRegion(storageAccount.regionName())
                                    .createdDate(storageAccount.creationTime())
                                    .containerName(container.name())
                                    .publicAccess(container.publicAccess().toString())
                                    .containerType(container.type())
                                    .build();
                            response.add(storageDetails);
                        })
        );
        return response;
    }


    /**
     * Feature: List all Database Servers with the respective DBs details for the tenant
     *
     * @param tenantName
     * @return
     */
    public List<DBServerDTO> listAllServerWithDBsForTenant(String tenantName) {
        List<DBServerDTO> response = new ArrayList<>();
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findByWsTenantName(tenantName)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided tenant: " + tenantName));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        azureResourceManager.sqlServers().list().forEach(sqlServer -> {
            DBServerDTO serverDTO = DBServerDTO.builder()
                    .serverId(sqlServer.id())
                    .serverName(sqlServer.name())
                    .serverType(sqlServer.type())
                    .region(sqlServer.region().name())
                    .serverVersion(sqlServer.version())
                    .databases(sqlServer.databases().list().stream()
                            .map(sqlDatabase -> DatabaseDTO.builder()
                                    .databaseId(sqlDatabase.id())
                                    .databaseName(sqlDatabase.name())
                                    .databaseType(sqlDatabase.innerModel().type())
                                    .status(sqlDatabase.status().toString())
                                    .build())
                            .collect(Collectors.toList()))
                    .build();

            response.add(serverDTO);
        });
        return response;
    }


    /**
     * Feature: List all Roles with the respective permissions for the Tenant
     *
     * @param tenantName
     * @return
     */
    public List<RolesWithPermissionsResponse> listAllRolesForTenant(String tenantName) {
        List<RolesWithPermissionsResponse> response = new ArrayList<>();
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findByWsTenantName(tenantName)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided tenant: " + tenantName));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        PagedIterable<RoleDefinition> roles = azureResourceManager.accessManagement().roleDefinitions().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        roles.forEach(roleDefinition ->
                response.add(RolesWithPermissionsResponse.builder()
                        .roleId(roleDefinition.id())
                        .roleName(roleDefinition.name())
                        .type(roleDefinition.type())
                        .permissions(roleDefinition.permissions())
                        .isCustom(isCustomRole(roleDefinition.id()))
                        .build())
        );
        return response;
    }


    /**
     * Feature: LIST ALL VMs FOR SPECIFIC USER/GROUP (aka Principle)
     *
     * @param azureUserId -> principle-id in the azure-resource context
     * @return
     */
    public List<VirtualMachine> listAllVmsForPrinciple(String azureUserId) {
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findAzureUserCredentialUsingAzureUserId(azureUserId)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided azure user: " + azureUserId));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        List<String> vmResourceIds = getAllRoleAssignmentForPrinciple(azureResourceManager, azureUserId)
                .stream()
                .filter(roleAssignment -> roleAssignment.scope().contains("Microsoft.Compute/virtualMachines"))
                .map(RoleAssignment::scope)
                .toList();
        // Filter VMs based on the role assignments (i.e., only those the user has access to)
        List<VirtualMachine> userVms = azureResourceManager.virtualMachines().list().stream()
                .filter(vm -> vmResourceIds.contains(vm.id()))
                .collect(Collectors.toList());
        return userVms;
    }


    /**
     * Feature: LIST ALL SERVERS WITH DBs FOR SPECIFIC USER/GROUP (aka Principle)
     *
     * @param azureUserId -> principle-id in the azure-resource context
     * @return
     */
    public List<DBServerDTO> listAllServerWithDBsForPrinciple(String azureUserId) {
        List<DBServerDTO> response = new ArrayList<>();
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findAzureUserCredentialUsingAzureUserId(azureUserId)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided azure user: " + azureUserId));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        List<String> sqlServerResourceIds = getAllRoleAssignmentForPrinciple(azureResourceManager, azureUserId)
                .stream()
                .map(RoleAssignment::scope)
                .filter(scope -> scope.contains("Microsoft.Sql/servers"))
                .toList();
        // Iterate through all SQL servers and filter based on the role assignments (i.e., only those the user has access to)
        azureResourceManager.sqlServers().list().stream()
                .filter(sqlServer -> sqlServerResourceIds.contains(sqlServer.id()))
                .forEach(sqlServer -> {
                    DBServerDTO serverDTO = DBServerDTO.builder()
                            .serverId(sqlServer.id())
                            .serverName(sqlServer.name())
                            .serverType(sqlServer.type())
                            .region(sqlServer.region().name())
                            .serverVersion(sqlServer.version())
                            .databases(sqlServer.databases().list().stream()
                                    .map(sqlDatabase -> DatabaseDTO.builder()
                                            .databaseId(sqlDatabase.id())
                                            .databaseName(sqlDatabase.name())
                                            .databaseType(sqlDatabase.innerModel().type())
                                            .status(sqlDatabase.status().toString())
                                            .build())
                                    .collect(Collectors.toList()))
                            .build();

                    response.add(serverDTO);
                });

        return response;
    }


    /**
     * Feature: LIST ALL STORAGES FOR SPECIFIC USER/GROUP (aka Principle)
     *
     * @param azureUserId -> principle-id in the azure-resource context
     * @return
     */
    public List<StorageAccountDTO> listAllStorageDetailsForPrinciple(String azureUserId) {
        List<StorageAccountDTO> response = new ArrayList<>();
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findAzureUserCredentialUsingAzureUserId(azureUserId)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided azure user: " + azureUserId));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        List<String> storageAccountResourceIds = getAllRoleAssignmentForPrinciple(azureResourceManager, azureUserId)
                .stream()
                .map(RoleAssignment::scope)
                .filter(scope -> scope.contains("Microsoft.Storage/storageAccounts"))
                .toList();
        azureResourceManager.storageAccounts().list().stream()
                .filter(storageAccount -> storageAccountResourceIds.contains(storageAccount.id()))
                .forEach(storageAccount -> {
                    azureResourceManager.storageBlobContainers()
                            .list(storageAccount.resourceGroupName(), storageAccount.name())
                            .forEach(container -> {
                                StorageAccountDTO storageDetails = StorageAccountDTO.builder()
                                        .storageAccountId(storageAccount.id())
                                        .storageAccountName(storageAccount.name())
                                        .storageAccountRegion(storageAccount.regionName())
                                        .createdDate(storageAccount.creationTime())
                                        .containerName(container.name())
                                        .publicAccess(container.publicAccess().toString())
                                        .containerType(container.type())
                                        .build();
                                response.add(storageDetails);
                            });
                });
        return response;
    }


    /**
     * Feature: LIST USERS ASSOCIATED WITH SPECIFIC PERMISSION
     *
     * @param permissionName
     * @param tenantName
     * @return
     */
    public List<String> listUsersForPermission(String permissionName, String tenantName) {
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findByWsTenantName(tenantName)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided tenant: " + tenantName));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        PagedIterable<RoleAssignment> roleAssignments = azureResourceManager.accessManagement().roleAssignments().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        return roleAssignments.stream()
                .filter(roleAssignment -> {
                    RoleDefinition roleDefinition = azureResourceManager.accessManagement().roleDefinitions().getById(roleAssignment.roleDefinitionId());
                    return roleDefinition.name().equalsIgnoreCase(permissionName);
                })
                .map(RoleAssignment::principalId)
                .collect(Collectors.toList());
    }


    /**
     * Feature: LIST GROUPS ASSOCIATED WITH SPECIFIC PERMISSION
     *
     * @param permissionName
     * @param tenantName
     * @return
     */
    public List<String> listGroupsForPermission(String permissionName, String tenantName) {
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findByWsTenantName(tenantName)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided tenant: " + tenantName));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        PagedIterable<RoleAssignment> roleAssignments = azureResourceManager.accessManagement().roleAssignments().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        return roleAssignments.stream()
                .filter(roleAssignment -> {
                    RoleDefinition roleDefinition = azureResourceManager.accessManagement().roleDefinitions().getById(roleAssignment.roleDefinitionId());
                    return roleDefinition.name().equalsIgnoreCase(permissionName);
                })
                .map(RoleAssignment::principalId)
                .filter(principal -> principal != null && principal.contains("group")) // Filter for groups
                .collect(Collectors.toList());
    }


    /**
     * Feature: RESPONSE DETAILS ABOUT A SPECIFIC PERMISSION
     *
     * @param permissionName
     * @param tenantName
     * @return
     */
    public RoleDefinition getPermissionDetails(String permissionName, String tenantName) {
        AzureUserCredential azureUserCredential = azureUserCredentialRepository
                .findByWsTenantName(tenantName)
                .orElseThrow(() -> new RuntimeException("No azure credentials found for provided tenant: " + tenantName));
        AzureResourceManager azureResourceManager = getAzureResourceManager(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), azureUserCredential.getTenantId(), azureUserCredential.getSubscriptionId());
        return azureResourceManager.accessManagement().roleDefinitions().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()))
                .stream()
                .filter(role -> role.name().equalsIgnoreCase(permissionName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Role not found"));
    }


    private PagedIterable<RoleAssignment> getAllRoleAssignmentForPrinciple(AzureResourceManager azureResourceManager, String principleId) {
        return azureResourceManager.accessManagement().roleAssignments().listByServicePrincipal(principleId);
    }

    private boolean isCustomRole(String roleId) {
        String builtInPrefix = "/providers/Microsoft.Authorization/roleDefinitions/";
        return !roleId.startsWith(builtInPrefix);
    }

}
