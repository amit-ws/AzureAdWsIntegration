package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.entity.AzureUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureUserRepository extends JpaRepository<AzureUser, Integer> {

    List<AzureUser> findAllByAzureTenant(AzureTenant azureTenant);
}
