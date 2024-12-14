package com.ws.azureAdIntegration.service;

import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.azureResourcesIntegration.service.AzureResourceSyncService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
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

    @Autowired
    public AzureSyncControlService(AzureADSyncService azureADSyncService, AzureResourceSyncService azureResourceSyncService, AzureUserCredentialRepository azureUserCredentialRepository) {
        this.azureADSyncService = azureADSyncService;
        this.azureResourceSyncService = azureResourceSyncService;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
    }

    @Async
    @Transactional
    public void syncAzureData(GraphServiceClient<Request> graphClient, AzureUserCredential azureUserCredential) {
        AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredential, graphClient);
        azureADSyncService.syncAzureADData(azureTenant);
        if (Optional.ofNullable(azureUserCredential.getSubscriptionId()).filter(subscriptionId -> !subscriptionId.isEmpty()).isPresent()) {
            azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredential);
        }
    }

    /* on demand sync */
    @Transactional
    public void syncAzureData(String wsTenantName) {
        AzureUserCredential azureUserCredential = azureUserCredentialRepository.findByWsTenantName(wsTenantName)
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found!"));
        String clientSecret = Optional.ofNullable(azureUserCredential.getClientSecret())
                .map(secret -> {
                    try {
                        return EncryptionUtil.decrypt(secret);
                    } catch (Exception e) {
                        log.error("Decryption error: ", e.getMessage());
                        throw new RuntimeException("Failed to decrypt client secret");
                    }
                })
                .orElseThrow(() -> new RuntimeException("Decrypted cClient secret found to be null"));
        azureUserCredential.setClientSecret(clientSecret);
        AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredential, null);
        azureADSyncService.syncAzureADData(azureTenant);
        azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredential);
    }
}
