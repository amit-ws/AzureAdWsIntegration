package com.ws.azureResourcesIntegration.service;


import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.service.AzureADService;
import com.ws.azureResourcesIntegration.entities.AzureServer;
import com.ws.azureResourcesIntegration.entities.AzureStorageAccount;
import com.ws.azureResourcesIntegration.entities.AzureVM;
import com.ws.azureResourcesIntegration.repository.AzureDatabaseRepository;
import com.ws.azureResourcesIntegration.repository.AzureServerRepository;
import com.ws.azureResourcesIntegration.repository.AzureStorageRepository;
import com.ws.azureResourcesIntegration.repository.AzureVMRepository;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Azure Resource feature service
 */
@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceService {
    final AzureVMRepository azureVMRepository;
    final AzureStorageRepository azureStorageRepository;
    final AzureServerRepository azureServerRepository;
    final AzureDatabaseRepository azureDatabaseRepository;
    final AzureADService azureADService;

    @Autowired
    public AzureResourceService(AzureVMRepository azureVMRepository, AzureStorageRepository azureStorageRepository, AzureServerRepository azureServerRepository, AzureDatabaseRepository azureDatabaseRepository, AzureADService azureADService) {
        this.azureVMRepository = azureVMRepository;
        this.azureStorageRepository = azureStorageRepository;
        this.azureServerRepository = azureServerRepository;
        this.azureDatabaseRepository = azureDatabaseRepository;
        this.azureADService = azureADService;
    }

    public List<AzureVM> getAllVirtualMachines(String tenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingwsTenantEmail(tenantName);
        return azureVMRepository.findAllByAzureTenant(azureTenant);
    }

    public List<AzureStorageAccount> getStorages(String tenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingwsTenantEmail(tenantName);
        return azureStorageRepository.findAllByAzureTenant(azureTenant);
    }

    public List<AzureServer> getServersWithDatavses(String tenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingwsTenantEmail(tenantName);
        return azureServerRepository.findAllByAzureTenant(azureTenant);
    }
}
