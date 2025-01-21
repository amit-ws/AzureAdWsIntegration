package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse;
import com.ws.projection.UserGroupAndRolesProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureUserRepository extends JpaRepository<AzureUser, Integer> {

    Optional<AzureUser> findByAzureId(String azureUserId);
    List<AzureUser> findAllByAzureTenant(AzureTenant azureTenant);

    void deleteAllByAzureTenant(AzureTenant azureTenant);

    Optional<AzureUser> findByUserPrincipalName(String username);

    List<AzureUser> findAllByWsTenantName(String tenantName);

    @Query("SELECT DISTINCT new com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse(au.id, au.displayName) " +
            "FROM AzureRoleAssignment ara " +
            "INNER JOIN AzureUser au ON ara.assignee = au.azureId " +
            "WHERE ara.wsTenantName= :wsTenantName and ara.principalType = 'User' and ara.azureRoleDefinitionPathId ILIKE %:wsRoleId " +
            "ORDER BY au.displayName")
    List<AzureRolePrincipleAssociationResponse> getAzureUserNameAndIdAssociatedWithRoleDefinitionId(String wsRoleId, String wsTenantName);


    @Query(value =
            "SELECT DISTINCT au.id AS id , au.azure_id AS azureUserId , au.display_name AS displayName , au.synced_at AS syncedAt, " +
                    "au.user_principal_name as userPrincipalName, au.created_date_time as createdDateTime, " +
                    "ARRAY_AGG(DISTINCT ag.display_name) AS groups, ARRAY_AGG(DISTINCT ard.role_name) AS roles  " +
                    "FROM azure_user au " +
                    "LEFT JOIN azure_user_group_membership augm on au.id = augm.user_id " +
                    "LEFT JOIN azure_group ag on augm.group_id = ag.id " +
                    "LEFT JOIN azure_role_assignment ara on ara.assignee = au.azure_id " +
                    "LEFT JOIN azure_role_definition ard on ara.azure_role_definition_path_id = ard.role_path_id " +
                    "WHERE au.ws_tenant_name = :wsTenantName AND au.ws_azure_tenant_id = :azureTenantId AND (au.azure_id = COALESCE(:azureUserId , au.azure_id)) " +
                    "GROUP BY au.id, au.azure_id, au.display_name, au.synced_at, au.user_principal_name,  au.created_date_time " +
                    "ORDER BY au.display_name",
            nativeQuery = true)
    List<UserGroupAndRolesProjection> getUserDetailsWithGroupNamesAndRoleNamesUsingTenantName(String wsTenantName, Integer azureTenantId, String azureUserId);
}
