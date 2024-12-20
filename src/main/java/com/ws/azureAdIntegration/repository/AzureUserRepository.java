package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureUserRepository extends JpaRepository<AzureUser, Integer> {

    List<AzureUser> findAllByAzureTenant(AzureTenant azureTenant);

    void deleteAllByAzureTenant(AzureTenant azureTenant);
    Optional<AzureUser> findByUserPrincipalName(String username);

    List<AzureUser> findAllByWsTenantName(String tenantName);

    @Query("SELECT new com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse(au.id, au.displayName) FROM AzureRoleAssignment ara LEFT JOIN AzureUser au ON ara.assignee = au.azureId " +
            "WHERE ara.wsTenantName= :wsTenantName and ara.principalType = 'User' and ara.azureRoleDefinitionId = :wsRoleId " +
            "ORDER BY au.displayName")
    List<AzureRolePrincipleAssociationResponse> getAzureUserNameAndIdAssociatedWithRoleId(String wsRoleId, String wsTenantName);

}
