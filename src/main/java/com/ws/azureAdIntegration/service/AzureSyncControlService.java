package com.ws.azureAdIntegration.service;

import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.repository.AzureTenantRepository;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureResourcesIntegration.service.AzureResourceSyncService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureSyncControlService {
    final AzureADSyncService azureADSyncService;
    final AzureResourceSyncService azureResourceSyncService;
    final AzureUserCredentialRepository azureUserCredentialRepository;
    final AzureTenantRepository azureTenantRepository;
    final AzureUserCredentialService azureUserCredentialService;
    final BackendApplicationLogservice backendApplicationLogservice;

    @Autowired
    public AzureSyncControlService(AzureADSyncService azureADSyncService, AzureResourceSyncService azureResourceSyncService, AzureUserCredentialRepository azureUserCredentialRepository, AzureTenantRepository azureTenantRepository, AzureUserCredentialService azureUserCredentialService, BackendApplicationLogservice backendApplicationLogservice) {
        this.azureADSyncService = azureADSyncService;
        this.azureResourceSyncService = azureResourceSyncService;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.azureTenantRepository = azureTenantRepository;
        this.azureUserCredentialService = azureUserCredentialService;
        this.backendApplicationLogservice = backendApplicationLogservice;
    }

    @Async
    @Transactional
    public void syncAzureData(GraphServiceClient<Request> graphClient, AzureUserCredentialDTO azureUserCredentialDTO) {
        log.info("Thread name: {}", Thread.currentThread().getName());
        AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, graphClient);
        azureADSyncService.syncAzureADData(azureTenant);
        if (Optional.ofNullable(azureUserCredentialDTO.getSubscriptionId()).filter(subscriptionId -> !subscriptionId.isEmpty()).isPresent()) {
            azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredentialDTO);
        }
    }


    /* Sync Azure-Resources data */
    @Async
    @Transactional
    public void syncAzureResourcesData(AzureUserCredentialDTO azureUserCredentialDTO) {
        log.info("Thread name for syncAzureResourcesData: {}", Thread.currentThread().getName());
        AzureTenant azureTenant = azureTenantRepository
                .findByWsTenantName(azureUserCredentialDTO.getWsTenantName())
                .orElseGet(() -> azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, null));
        azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredentialDTO);
    }


    /* on demand sync */
    @Transactional
    public void syncAzureData(String wsTenantName) {
        AzureUserCredentialDTO azureUserCredentialDTO = azureUserCredentialService.findWSTenantIdWithDecryptedSecret(wsTenantName);
        AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, null);
        azureADSyncService.syncAzureADData(azureTenant);
        Optional.ofNullable(azureUserCredentialDTO.getSubscriptionId())
                .filter(StringUtils::isNotEmpty)
                .ifPresentOrElse(subscriptionId -> azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredentialDTO),
                        () -> backendApplicationLogservice.saveAuditLog(
                                wsTenantName, "demo@gmail.com", "ADD", Constant.AZURE_RESOURCE_DATA_SYNC_SKIPPED, "Info"));
    }
}
