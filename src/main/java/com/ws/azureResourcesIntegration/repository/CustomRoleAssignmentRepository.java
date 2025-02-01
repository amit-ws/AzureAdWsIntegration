package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.dto.CustomRoleAssignmentDTO;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomRoleAssignmentRepository extends JpaRepository<CustomRoleAssignment, Integer> {
    Optional<CustomRoleAssignment> findByAssigneeAndScopeAndAzureRoleDefinitionPathIdAndStatusNot(String assignee, String scope, String roleDefPathId, RequestStatus status);
    Optional<CustomRoleAssignment> findByAssigneeAndScopeAndAzureRoleDefinitionPathIdAndStatusNotIn(String assignee, String scope, String roleDefPathId, List<RequestStatus> status);

    //    List<CustomRoleAssignment> findAllByWsTenantNameAndStatusOrderByCreatedOnDesc(String wsTenantName, RequestStatus status);
    List<CustomRoleAssignment> findAllByStatus(RequestStatus status);

    @Query("SELECT c FROM CustomRoleAssignment c WHERE c.wsTenantName = :wsTenantName AND (:status IS NULL OR c.status = :status) ORDER BY c.requestedAt DESC")
    List<CustomRoleAssignment> findAllByWsTenantNameAndStatus(@Param("wsTenantName") String wsTenantName, @Param("status") RequestStatus status);

    @Query("SELECT new com.ws.azureResourcesIntegration.dto.CustomRoleAssignmentDTO(c.id, c.azureId, c.azureRoleAssignmentPathId, c.description, c.assignee, " +
            "c.principalType, c.scope, c.scopeType, c.condition, c.azureRoleDefinitionPathId, c.wsTenantName, c.status, c.requestedAt, c.updatedAt, c.validFrom," +
            " c.validTo, c.expiryTimeAmount, c.userEmail, au.displayName, ard.roleName) " +
            "FROM CustomRoleAssignment c " +
            "LEFT JOIN AzureRoleDefinition ard ON c.azureRoleDefinitionPathId = ard.rolePathId " +
            "LEFT JOIN AzureUser au ON c.assignee = au.azureId " +
            "WHERE c.wsTenantName = :wsTenantName AND (:status IS NULL OR c.status = :status)  AND (:assignee IS NULL OR c.assignee = :assignee) " +
            "ORDER BY c.requestedAt DESC")
    List<CustomRoleAssignmentDTO> findAllByWsTenantNameAndStatus2(@Param("wsTenantName") String wsTenantName, @Param("status") RequestStatus status, String assignee);

    @Modifying
    void deleteAllByWsTenantName(String wsTenantMame);

}
