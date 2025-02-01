package com.ws.azureAdIntegration.service;


import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.repository.AzureTenantRepository;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureTenantService {
    final AzureTenantRepository azureTenantRepository;

    @Autowired
    public AzureTenantService(AzureTenantRepository azureTenantRepository) {
        this.azureTenantRepository = azureTenantRepository;
    }

    public AzureTenant getAzureTenantUsingWsTenantName(String wsTenantName) {
        return azureTenantRepository.findByWsTenantName(wsTenantName)
                .orElseThrow(() -> new RuntimeException("No tenant found with provided tenantName: " + wsTenantName));
    }
}
