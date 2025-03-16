package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.dto.CustomRoleAssignmentDTO;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import com.ws.projection.CustomRoleAssignmentProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    @Query(value = "SELECT  " +
            "ARRAY_AGG(ard.role_name) AS roles, " +
            "array_agg(cara.azure_id) AS assignmentIds, " +
            "cara.expiry_time_amount AS expirtyTime, " +
            "cara.valid_from AS validFrom, " +
            "cara.valid_to AS validTo, " +
            "cara.status , cara.user_email AS userEmail , cara.assignee , au.display_name AS displayName , cara.\"scope\" AS scope , cara.requested_at AS requestedAt,  cara.ws_tenant_name AS wsTenantName " +
            "FROM  custom_azure_role_assignment cara  " +
            "LEFT JOIN azure_role_definition ard ON cara.azure_role_definition_path_id = ard.role_path_id  " +
            "LEFT JOIN azure_user au ON  cara.assignee = au.azure_id  " +
            "WHERE cara.ws_tenant_name = :wsTenantName AND (:status IS NULL OR cara.status = :status) AND (:assignee IS NULL OR cara.assignee = :assignee) " +
            "GROUP BY cara.ws_tenant_name , cara.user_email , cara.assignee , cara.\"scope\" , cara.requested_at , au.display_name , cara.status, cara.valid_from , cara.valid_to , cara.expiry_time_amount " +
            "ORDER BY cara.requested_at DESC"
            , nativeQuery = true)
    List<CustomRoleAssignmentProjection> filterAllByWsTenantNameAndParams(@Param("wsTenantName") String wsTenantName, @Param("status") String status, String assignee);

    @Modifying
    void deleteAllByWsTenantName(String wsTenantMame);

    List<CustomRoleAssignment> findAllByAssigneeAndScope(String assignee, String scope);

    List<CustomRoleAssignment> findAllByAssigneeAndScopeAndAzureRoleDefinitionPathIdInAndStatusInAndWsTenantName(String assignee, String scope, Set<String> roleDefPathIds, List<RequestStatus> status, String wsTenantName);

    List<CustomRoleAssignment> findAllByAssigneeAndAzureRoleDefinitionPathIdAndScopeInAndWsTenantNameAndStatus(String assignee, String rolePathId, List<String> scopeIds, String wsTenantName, RequestStatus status);

    List<CustomRoleAssignment> findAllByWsTenantNameAndAzureIdIn(String wsTenantName, List<String> azureIDs);


}
