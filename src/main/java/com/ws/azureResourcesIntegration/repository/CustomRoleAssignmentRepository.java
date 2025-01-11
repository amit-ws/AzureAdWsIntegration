package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.constant.CustomRoleAssignmentStatus;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;

@Repository
public interface CustomRoleAssignmentRepository extends JpaRepository<CustomRoleAssignment, Integer> {
    @Query(value = "INSERT INTO custom_azure_role_assignment (assignee, scope, azure_role_definition_path_id, azure_id, principal_type, scope_type, description, status, created_on, expiry_time_amount, ws_tenant_name, ws_azure_subscription_id) " +
            "VALUES (:assignee, :scope, :azureRoleDefinitionPathId, :azureId, :principalType, :scopeType, :description, :status, :createdOn, :expiryTimeAmount, :wsTenantName, :subscriptionId) " +
            "ON CONFLICT (assignee, scope, azure_role_definition_path_id) " +
            "DO UPDATE SET azure_id = excluded.azure_id, principal_type = excluded.principal_type, scope_type = excluded.scope_type, description = excluded.description, status = excluded.status, created_on = excluded.created_on, expiry_time_amount = excluded.expiry_time_amount, ws_tenant_name = excluded.ws_tenant_name, ws_azure_subscription_id = excluded.ws_azure_subscription_id " +
            "RETURNING *",
            nativeQuery = true)
    CustomRoleAssignment saveOrUpdate(@Param("assignee") String assignee, @Param("scope") String scope, @Param("azureRoleDefinitionPathId") String azureRoleDefinitionPathId, @Param("azureId") String azureId,
                                      @Param("principalType") String principalType, @Param("scopeType") String scopeType, @Param("description") String description, @Param("status") String status,
                                      @Param("createdOn") OffsetDateTime createdOn, @Param("expiryTimeAmount") Long expiryTimeAmount, @Param("wsTenantName") String wsTenantName, @Param("subscriptionId") Integer subscriptionId);

    List<CustomRoleAssignment> findAllByWsTenantNameAndStatusOrderByCreatedOnDesc(String wsTenantName, CustomRoleAssignmentStatus status);
}
