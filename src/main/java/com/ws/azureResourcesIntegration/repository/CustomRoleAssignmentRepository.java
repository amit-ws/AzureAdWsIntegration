package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomRoleAssignmentRepository extends JpaRepository<CustomRoleAssignment, Integer> {
    Optional<CustomRoleAssignment> findByAssigneeAndScopeAndAzureRoleDefinitionPathIdAndStatusNot(String assignee, String scope, String roleDefPathId, RequestStatus status);
    List<CustomRoleAssignment> findAllByWsTenantNameAndStatusOrderByCreatedOnDesc(String wsTenantName, RequestStatus status);
    List<CustomRoleAssignment> findAllByStatus(RequestStatus status);
}
