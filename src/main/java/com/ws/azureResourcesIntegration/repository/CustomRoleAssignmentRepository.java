package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.constant.CustomRoleAssignmentStatus;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomRoleAssignmentRepository extends JpaRepository<CustomRoleAssignment, Integer> {
    Optional<CustomRoleAssignment> findByAzureId(String azureId);

    List<CustomRoleAssignment> findAllByWsTenantNameAndStatus(String wsTenantName, CustomRoleAssignmentStatus status);

    @Modifying
    void deleteByAzureRoleAssignmentPathId(String pathId);

}
