package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureRoleAssignmentRepository extends JpaRepository<AzureRoleAssignment, Integer> {
    void deleteAllByAzureTenant(AzureTenant azureTenant);

    @Modifying
    void deleteByAzureRoleAssignmentPathId(String pathId);

    List<AzureRoleAssignment> findAllByWsTenantNameOrderByCreatedOnDesc(String wsTenantName);

    Optional<AzureRoleAssignment> findByAzureId(String azureId);
}
