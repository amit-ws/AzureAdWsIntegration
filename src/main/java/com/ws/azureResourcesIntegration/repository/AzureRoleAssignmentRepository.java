package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.dto.CustomRoleAssignmentDTO;
import com.ws.azureResourcesIntegration.entities.AzureRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureRoleAssignmentRepository extends JpaRepository<AzureRoleAssignment, Integer> {
    void deleteAllByAzureTenant(AzureTenant azureTenant);

    @Modifying
    void deleteByAzureRoleAssignmentPathId(String pathId);

    List<AzureRoleAssignment> findAllByWsTenantNameOrderByCreatedOnDesc(String wsTenantName);

    @Query("SELECT new com.ws.azureResourcesIntegration.dto.CustomRoleAssignmentDTO(a.id, a.azureId, a.azureRoleAssignmentPathId, a.description, a.assignee, " +
            "a.principalType, a.scope, a.scopeType, a.condition, a.azureRoleDefinitionPathId, a.wsTenantName, au.displayName, ard.roleName) " +
            "FROM AzureRoleAssignment a " +
            "LEFT JOIN AzureUser au on a.assignee = au.azureId " +
            "LEFT JOIN AzureRoleDefinition ard on a.azureRoleDefinitionPathId = ard.rolePathId " +
            "WHERE a.wsTenantName = :wsTenantName AND (:assignee IS NULL OR a.assignee = :assignee) ORDER BY a.createdOn DESC")
    List<CustomRoleAssignmentDTO> findAllByWsTenantNameAndAssignee(String wsTenantName, String assignee);

    Optional<AzureRoleAssignment> findByAzureId(String azureId);
}
