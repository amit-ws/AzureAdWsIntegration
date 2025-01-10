package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureGroup;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse;
import com.ws.projection.UserGroupsNameProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface AzureGroupRepository extends JpaRepository<AzureGroup, Integer> {

    List<AzureGroup> findAllByAzureTenant(AzureTenant azureTenant);

    void deleteAllByAzureTenant(AzureTenant azureTenant);

    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse(ag.id, ag.displayName) FROM AzureRoleAssignment ara LEFT JOIN AzureGroup ag ON ara.assignee = ag.azureId " +
            "WHERE ara.wsTenantName= :wsTenantName and ara.principalType = 'Group' and ara.azureRoleDefinitionPathId = :wsRoleId " +
            "ORDER BY ag.displayName")
    List<AzureRolePrincipleAssociationResponse> getAzureUserNameAndIdAssociatedWithRoleId(String wsRoleId, String wsTenantName);

    @Query(value =
            "SELECT ag.id, ag.azure_id as azureGroupId, ag.display_name as displayName " +
                    "FROM azure_user au " +
                    "INNER JOIN azure_user_group_membership agm ON au.id = agm.user_id " +
                    "INNER JOIN azure_group ag ON agm.group_id = ag.id " +
                    "WHERE au.id = :userId " +
                    "ORDER BY ag.display_name"
            , nativeQuery = true)
    List<UserGroupsNameProjection> getGroupNamesForUser(Integer userId);
}
