package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomRoleAssignmentRepository extends JpaRepository<CustomRoleAssignment, Integer> {
    Optional<CustomRoleAssignment> findByAssigneeAndScopeAndAzureRoleDefinitionPathIdAndStatusNot(String assignee, String scope, String roleDefPathId, RequestStatus status);

    //    List<CustomRoleAssignment> findAllByWsTenantNameAndStatusOrderByCreatedOnDesc(String wsTenantName, RequestStatus status);
    List<CustomRoleAssignment> findAllByStatus(RequestStatus status);

    @Query("SELECT c FROM CustomRoleAssignment c WHERE c.wsTenantName = :wsTenantName AND (:status IS NULL OR c.status = :status) ORDER BY c.createdOn DESC")
    List<CustomRoleAssignment> findAllByWsTenantNameAndStatus(@Param("wsTenantName") String wsTenantName, @Param("status") RequestStatus status);

    @Query("SELECT c FROM CustomRoleAssignment c WHERE c.wsTenantName = :wsTenantName AND (:status IS NULL OR c.status = :status)  " +
            "AND (:assignee IS NULL OR c.assignee = :assignee) ORDER BY c.createdOn DESC")
    List<CustomRoleAssignment> findAllByWsTenantNameAndStatus2(@Param("wsTenantName") String wsTenantName, @Param("status") RequestStatus status, String assignee);


}
