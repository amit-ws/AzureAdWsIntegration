package com.ws.azureAdIntegration.service;

import com.microsoft.graph.models.Organization;
import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureTenantRepository;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureAdIntegration.util.AzureEntityUtil;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.azureResourcesIntegration.service.AzureResourceSyncService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureSyncService {
    String wsTenantName;
    String tenantEmail = "dummy@gmail.com";
    GraphServiceClient graphClient;
    final AzureADSyncService azureADSyncService;
    final AzureResourceSyncService azureResourceSyncService;
    final AzureUserCredentialRepository azureUserCredentialRepository;
    final BackendApplicationLogservice backendApplicationLogservice;
    final AzureTenantRepository azureTenantRepository;
    final AzureAuthUtil azureAuthUtil;


    @Autowired
    public AzureSyncService(AzureADSyncService azureADSyncService, AzureResourceSyncService azureResourceSyncService, AzureUserCredentialRepository azureUserCredentialRepository, BackendApplicationLogservice backendApplicationLogservice, AzureTenantRepository azureTenantRepository, AzureAuthUtil azureAuthUtil) {
        this.azureADSyncService = azureADSyncService;
        this.azureResourceSyncService = azureResourceSyncService;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureTenantRepository = azureTenantRepository;
        this.azureAuthUtil = azureAuthUtil;
    }


    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncAzureData(String wsTenantName, GraphServiceClient graphClient, AzureUserCredential azureUserCredential) {
        this.wsTenantName = wsTenantName;
        this.graphClient = graphClient;
        AzureTenant azureTenant = syncTenantData(azureUserCredential.getTenantId());
        azureADSyncService.syncAzureADData(azureTenant);
        if (Optional.ofNullable(azureUserCredential.getSubscriptionId()).filter(s -> !s.isEmpty()).isEmpty()) {
            azureResourceSyncService.syncAzureResourceData(azureTenant,this.wsTenantName, azureUserCredential);
        }
    }

    /* on demand sync */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncAzureData(String wsTenantName) {
        AzureUserCredential azureUserCredential = Optional.ofNullable(azureUserCredentialRepository.findByWsTenantName(wsTenantName).get())
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

        // Validate Azure credentials
        log.info("Validating user's Azure-AD credentials..");
        this.graphClient = azureAuthUtil.validateAzureCredentials(azureUserCredential);
        AzureTenant azureTenant = syncTenantData(azureUserCredential.getTenantId());
        azureADSyncService.syncAzureADData(azureTenant);
        azureResourceSyncService.syncAzureResourceData(azureTenant, this.wsTenantName, azureUserCredential);
    }

    private AzureTenant syncTenantData(String tenantId) {
        Organization organization = this.graphClient.organization(tenantId)
                .buildRequest()
                .get();
        // delete the existing tenant and re-create whatever has been fetched this time
        azureTenantRepository.deleteByAzureIdAndWsTenantName(tenantId, wsTenantName);
        AzureTenant azureTenant = AzureEntityUtil.createAzureTenantFromGraphOrganization(organization, AzureTenant.builder().wsTenantName(this.wsTenantName).build());
        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_TENANT_SAVED, "Info");
        return azureTenantRepository.save(azureTenant);
    }
}
