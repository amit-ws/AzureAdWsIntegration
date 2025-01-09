package com.ws.azureAdIntegration.service;

import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.CreateAzureConfiguration;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.azureResourcesIntegration.service.AzureResourceSyncService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    final AzureUserRepository azureUserRepository;
    @Value("${spring.cloud.azure.active-directory.redirect-uri}")
    String redirectUri;
    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public AzureAuthService(AzureUserCredentialRepository azureUserCredentialRepository, BackendApplicationLogservice backendApplicationLogservice, AzureSyncControlService azureSyncControlService,
                            AzureResourceSyncService azureResourceSyncService, AzureAuthUtil azureAuthUtil, AzureUserEntityService azureUserEntityService, AzureUserRepository azureUserRepository) {
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureSyncControlService = azureSyncControlService;
        this.azureResourceSyncService = azureResourceSyncService;
        this.azureAuthUtil = azureAuthUtil;
        this.azureUserEntityService = azureUserEntityService;
        this.azureUserRepository = azureUserRepository;
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
//        GraphServiceClient<Request> graphClient = azureAuthUtil.validateAzureCredentials(tenantId, clientId, createAzureConfiguration.getClientSecret());
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
//        entityManager.flush();
//        entityManager.detach(azureUserCredential);
//        azureUserCredential.setClientSecret(createAzureConfiguration.getClientSecret());
        backendApplicationLogservice.saveAuditLog(wsTenantName, "dummy@gmail.com", Constant.ADD, Constant.AZURE_CREDENTIALS_SAVED, "Info");
//        log.info("Thread name: {}", Thread.currentThread().getName());
//        azureSyncControlService.syncAzureData(graphClient, azureUserCredential);
        return Collections.singletonMap("message", "Credentials configured successfully and Data sync started!");
    }


    public AzureUserCredential fetchAzureConfiguration(String tenantName) {
        AzureUserCredential azureUserCredential = Optional.ofNullable(getAzureUserCredentialForWSTenant(tenantName))
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found!"));
        azureUserCredential.setClientSecret(EncryptionUtil.getDecryptedKey(azureUserCredential.getClientSecret(), Constant.AZURE_CLIENT_SECRET));
        return azureUserCredential;
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
    public AzureUserCredential updateSubscriptionId(Integer credId, String subscriptionId) {
        AzureUserCredential azureUserCredential = azureUserCredentialRepository.findById(credId).orElseThrow(() -> new RuntimeException("No azure credentials found with provided id: " + credId));
        azureUserCredential.setSubscriptionId(subscriptionId);
        azureUserCredential.setUpdatedAt(new Date());
        backendApplicationLogservice.saveAuditLog(azureUserCredential.getWsTenantName(), "demo@gmail.com", "UPDATE", Constant.AZURE_SUBSCRIPTION_ID_UPDATED, "Info");
        azureUserCredentialRepository.save(azureUserCredential);
        entityManager.flush();
        backendApplicationLogservice.saveAuditLog(azureUserCredential.getWsTenantName(), "demo@gmail.com", "ADD", Constant.AZURE_RESOURCE_DATA_SYNC_START, "Info");
        azureSyncControlService.syncAzureResourcesData(azureUserCredential);
        return azureUserCredential;
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
