package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureGroup;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureGroupRepository extends JpaRepository<AzureGroup, Integer> {

    List<AzureGroup> findAllByAzureTenant(AzureTenant azureTenant);
    void deleteAllByAzureTenant(AzureTenant azureTenant);
    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse(ag.id, ag.displayName) FROM AzureRoleAssignment ara LEFT JOIN AzureGroup ag ON ara.assignee = ag.azureId " +
            "WHERE ara.wsTenantName= :wsTenantName and ara.principalType = 'Group' and ara.azureRoleDefinitionId = :wsRoleId " +
            "ORDER BY ag.displayName")
    List<AzureRolePrincipleAssociationResponse> getAzureUserNameAndIdAssociatedWithRoleId(String wsRoleId, String wsTenantName);

}
