package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.azure.resourcemanager.authorization.models.RoleDefinition;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceFeatureService {
    final AzureResourceAuthFactory azureResourceAuthFactory;
    @Value("${spring.cloud.azure.active-directory.client-id}")
    String clientId;

    @Value("${spring.cloud.azure.active-directory.client-secret}")
    String clientSecret;

    @Value("${spring.cloud.azure.active-directory.tenant-id}")
    String tenantId;

    @Value("${spring.cloud.azure.active-directory.subscription-id}")
    String subscriptionId;


    @Autowired
    public AzureResourceFeatureService(AzureResourceAuthFactory azureResourceAuthFactory) {
        this.azureResourceAuthFactory = azureResourceAuthFactory;
    }

    private AzureResourceManager getAzureResourceManager() {
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
}
