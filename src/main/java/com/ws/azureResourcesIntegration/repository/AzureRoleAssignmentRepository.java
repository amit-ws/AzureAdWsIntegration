package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureRoleAssignmentRepository extends JpaRepository<AzureRoleAssignment, Integer> {
}
