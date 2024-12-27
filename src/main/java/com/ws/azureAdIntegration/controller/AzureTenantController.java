package com.ws.azureAdIntegration.controller;

import com.ws.azureAdIntegration.repository.AzureTenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AzureTenantController {

    final AzureTenantRepository azureTenantRepository;

    @Autowired
    public AzureTenantController(AzureTenantRepository azureTenantRepository) {
        this.azureTenantRepository = azureTenantRepository;
    }

    @DeleteMapping("/delete/{tenantId}/{name}")
    public void deleteTeanant(String tenantId, String name){
        azureTenantRepository.deleteByAzureIdAndWsTenantName(tenantId, name);
    }
}
