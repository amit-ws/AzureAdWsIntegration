package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureAppRoles;
import com.ws.azureAdIntegration.entity.AzureApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AzureAppRolesRepository extends JpaRepository<AzureAppRoles, Integer> {
    List<AzureAppRoles> findAllByApplication(AzureApplication azureApplication);

}
