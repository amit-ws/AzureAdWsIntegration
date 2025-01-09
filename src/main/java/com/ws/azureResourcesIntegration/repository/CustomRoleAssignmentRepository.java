package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.constant.CustomRoleAssignmentStatus;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomRoleAssignmentRepository extends JpaRepository<CustomRoleAssignment, Integer> {
    List<CustomRoleAssignment> findAllByWsTenantNameAndStatus(String wsTenantName, CustomRoleAssignmentStatus status);
}
