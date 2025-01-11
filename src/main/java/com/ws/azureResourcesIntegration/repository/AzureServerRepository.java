package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureServer;
import com.ws.azureResourcesIntegration.entities.AzureStorageAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureServerRepository extends JpaRepository<AzureServer, Integer> {
    List<AzureServer> findAllByWsTenantName(String wsTenantName);

    @Query("SELECT DISTINCT asv " +
            "FROM AzureServer asv " +
            "INNER JOIN AzureRoleAssignment ara ON UPPER(asv.azureServerId) = UPPER(ara.scope) " +
            "WHERE ara.scopeType IN :scopeTypes AND ara.principalType = :principalType and ara.assignee = :assignee and asv.wsTenantName = :tenantName")
    List<AzureServer> getAzureServersWithDatabasesForPrinciple(List<String> scopeTypes, String principalType, String assignee, String tenantName);
}
