package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.entity.AzureUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureUserRepository extends JpaRepository<AzureUser, Integer> {

    List<AzureUser> findAllByAzureTenant(AzureTenant azureTenant);

    void deleteAllByAzureTenant(AzureTenant azureTenant);
    Optional<AzureUser> findByUserPrincipalName(String username);

    List<AzureUser> findAllByWsTenantName(String tenantName);

}
