package com.ws.azureAdIntegration.service;

import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.dto.CreateAzureConfiguration;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.azureResourcesIntegration.service.AzureResourceSyncService;
import com.ws.mapper.AzureEntitiesMapper;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureAuthService {
    final Logger log = LoggerFactory.getLogger(this.getClass());
    final AzureUserCredentialRepository azureUserCredentialRepository;
    final BackendApplicationLogservice backendApplicationLogservice;
    final AzureSyncControlService azureSyncControlService;
    final AzureResourceSyncService azureResourceSyncService;
    final AzureAuthUtil azureAuthUtil;
    final AzureUserEntityService azureUserEntityService;
    final AzureUserCredentialService azureUserCredentialService;
    final AzureUserRepository azureUserRepository;
    @Value("${spring.cloud.azure.active-directory.redirect-uri}")
    String redirectUri;

    @Autowired
    public AzureAuthService(AzureUserCredentialRepository azureUserCredentialRepository, BackendApplicationLogservice backendApplicationLogservice, AzureSyncControlService azureSyncControlService,
                            AzureResourceSyncService azureResourceSyncService, AzureAuthUtil azureAuthUtil, AzureUserEntityService azureUserEntityService, AzureUserRepository azureUserRepository, AzureUserCredentialService azureUserCredentialService) {
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureSyncControlService = azureSyncControlService;
        this.azureResourceSyncService = azureResourceSyncService;
        this.azureAuthUtil = azureAuthUtil;
        this.azureUserEntityService = azureUserEntityService;
        this.azureUserRepository = azureUserRepository;
        this.azureUserCredentialService = azureUserCredentialService;
    }

    @Transactional
    public Map creatAzureConfiguration(CreateAzureConfiguration createAzureConfiguration) {
        String subscriptionId = Optional.ofNullable(createAzureConfiguration.getSubscriptionId()).filter(subId -> !subId.isEmpty()).map(String::trim).orElse(null);
        String wsTenantName = createAzureConfiguration.getWsTenantName().trim();
        String clientId = createAzureConfiguration.getClientId().trim();
        String tenantId = createAzureConfiguration.getTenantId().trim();
        Optional.ofNullable(getAzureUserCredentialForWSTenant(wsTenantName))
                .ifPresent(credential -> {
                    if (StringUtils.equalsIgnoreCase(credential.getClientId(), clientId)) {
                        throw new RuntimeException("Azure credentials already exist for client ID: " + clientId);
                    }
                    if (StringUtils.isNotEmpty(credential.getSubscriptionId()) &&
                            StringUtils.equalsIgnoreCase(credential.getSubscriptionId(), subscriptionId)) {
                        throw new RuntimeException("Azure credentials already exist for subscription ID: " + subscriptionId);
                    }
                });
        log.info("Validating user's Azure-AD credentials..");
        GraphServiceClient<Request> graphClient = azureAuthUtil.validateAzureCredentials(tenantId, clientId, createAzureConfiguration.getClientSecret());
        AzureUserCredential azureUserCredential = azureUserCredentialRepository.save(
                AzureUserCredential.builder()
                        .clientId(clientId)
                        .clientSecret(EncryptionUtil.getEncryptedKey(createAzureConfiguration.getClientSecret(), Constant.AZURE_CLIENT_SECRET))
                        .tenantId(tenantId)
                        .subscriptionId(subscriptionId)
                        .wsTenantName(wsTenantName)
                        .createdAt(new Date())
                        .build()
        );
        backendApplicationLogservice.saveAuditLog(wsTenantName, "dummy@gmail.com", Constant.ADD, Constant.AZURE_CREDENTIALS_SAVED, "Info");
        AzureUserCredentialDTO azureUserCredentialDTO = azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(azureUserCredential);
        log.info("Thread name: {}", Thread.currentThread().getName());
        azureSyncControlService.syncAzureData(graphClient, azureUserCredentialDTO);
        return Collections.singletonMap("message", "Credentials configured successfully and Data sync started!");
    }


    public AzureUserCredentialDTO fetchAzureConfiguration(String tenantName) {
        return azureUserCredentialService.findWSTenantIdWithDecryptedSecret(tenantName);
    }


    public String generateAzureSSOUrl(String email) {
        azureUserEntityService.getAzureUserUsingEmail(email);
        AzureUserCredential azureUserCredential = getAzureUserCredentialForWSTenant(getAzureUserUsingEmail(email).getWsTenantName());

        return UriComponentsBuilder.fromHttpUrl("https://login.microsoftonline.com/")
                .pathSegment(azureUserCredential.getTenantId(), Constant.OAUTH, Constant.OAUTH_VERSION, Constant.OAUTH_TYPE)
                .queryParam(Constant.CLIENT_ID_PARAM, azureUserCredential.getClientId())
                .queryParam(Constant.RESPONSE_TYPE_PARAM, Constant.AZURE_RESPONSE_TYPE)
                .queryParam(Constant.REDIRECT_URI_PARAM, redirectUri)
                .queryParam(Constant.RESPONSE_MODE_PARAM, Constant.AZURE_RESPONSE_MODE)
                .queryParam(Constant.SCOPE_PARAM, URLEncoder.encode("offline_access User.Read Mail.Read", StandardCharsets.UTF_8))
                .toUriString();
    }

    @Transactional
    public AzureUserCredentialDTO updateSubscriptionId(Integer credId, String subscriptionId) {
        AzureUserCredential updatedCredential = updateSubscriptionIdInCredential(subscriptionId,
                azureUserCredentialRepository.findById(credId).orElseThrow(() -> new RuntimeException("No azure credentials found with provided id: " + credId)));
        backendApplicationLogservice.saveAuditLog(updatedCredential.getWsTenantName(), "demo@gmail.com", "UPDATE", Constant.AZURE_SUBSCRIPTION_ID_UPDATED, "Info");
        backendApplicationLogservice.saveAuditLog(updatedCredential.getWsTenantName(), "demo@gmail.com", "ADD", Constant.AZURE_RESOURCE_DATA_SYNC_START, "Info");
        AzureUserCredentialDTO azureUserCredentialDTO = azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(updatedCredential);
        azureSyncControlService.syncAzureResourcesData(azureUserCredentialDTO);
        return azureUserCredentialDTO;
    }

    private AzureUserCredential updateSubscriptionIdInCredential(String subscriptionId, AzureUserCredential azureUserCredential) {
        azureUserCredential.setSubscriptionId(subscriptionId);
        azureUserCredential.setUpdatedAt(new Date());
        return azureUserCredentialRepository.save(azureUserCredential);
    }


    private AzureUserCredential getAzureUserCredentialForWSTenant(String wsTenantName) {
        return azureUserCredentialRepository.findByWsTenantName(wsTenantName).orElse(null);
    }

    private AzureUser getAzureUserUsingEmail(String email) {
        return azureUserRepository.findByUserPrincipalName(email)
                .map(azureUser -> {
                    if (azureUser.getIsSSOEnabled() == null || !azureUser.getIsSSOEnabled()) {
                        throw new RuntimeException("Azure user not SSO enabled");
                    }
                    return azureUser;
                })
                .orElseThrow(() -> new RuntimeException(String.format("No Azure User found with provided email: %s", email)));
    }
}
