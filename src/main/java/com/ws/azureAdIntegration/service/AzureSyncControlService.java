package com.ws.azureAdIntegration.service;

import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureTenantRepository;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.azureResourcesIntegration.service.AzureResourceSyncService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
    @PersistenceContext
    EntityManager entityManager;

    @Autowired
    public AzureSyncControlService(AzureADSyncService azureADSyncService, AzureResourceSyncService azureResourceSyncService, AzureUserCredentialRepository azureUserCredentialRepository, AzureTenantRepository azureTenantRepository) {
        this.azureADSyncService = azureADSyncService;
        this.azureResourceSyncService = azureResourceSyncService;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.azureTenantRepository = azureTenantRepository;
    }

    @Async
    @Transactional
    public void syncAzureData(GraphServiceClient<Request> graphClient, AzureUserCredential azureUserCredential) {
        log.info("Thread name: {}", Thread.currentThread().getName());
        AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredential, graphClient);
        azureADSyncService.syncAzureADData(azureTenant);
        if (Optional.ofNullable(azureUserCredential.getSubscriptionId()).filter(subscriptionId -> !subscriptionId.isEmpty()).isPresent()) {
            azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredential);
        }
    }


    /* Sync Azure-Resources data */
    @Async
    @Transactional
    public void syncAzureResourcesData(AzureUserCredential azureUserCredential) {
        log.info("Thread name for syncAzureResourcesData: {}", Thread.currentThread().getName());
        entityManager.detach(azureUserCredential);
        AzureTenant azureTenant = azureTenantRepository
                .findByWsTenantName(azureUserCredential.getWsTenantName())
                .orElseGet(() -> azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredential, null));
        azureUserCredential.setClientSecret(EncryptionUtil.getDecryptedKey(azureUserCredential.getClientSecret(), Constant.AZURE_CLIENT_SECRET));
        azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredential);
    }


    /* on demand sync */
    @Transactional
    public void syncAzureData(String wsTenantName) {
        AzureUserCredential azureUserCredential = azureUserCredentialRepository.findByWsTenantName(wsTenantName)
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found!"));
        String clientSecret = EncryptionUtil.getDecryptedKey(azureUserCredential.getClientSecret(), Constant.AZURE_CLIENT_SECRET);
        azureUserCredential.setClientSecret(clientSecret);
        AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredential, null);
        azureADSyncService.syncAzureADData(azureTenant);
        Optional.ofNullable(azureUserCredential.getSubscriptionId())
                .filter(StringUtils::isNotEmpty)
                .ifPresentOrElse(subscriptionId -> azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredential),
                        () -> log.info("Skipped Azure-Resources data sync as no subscription-id was found"));
    }
}
