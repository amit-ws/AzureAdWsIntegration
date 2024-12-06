package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureGroup;
import com.ws.azureAdIntegration.entity.AzureTenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureGroupRepository extends JpaRepository<AzureGroup, Integer> {

    List<AzureGroup> findAllByAzureTenant(AzureTenant azureTenant);
    void deleteAllByAzureTenant(AzureTenant azureTenant);


}
