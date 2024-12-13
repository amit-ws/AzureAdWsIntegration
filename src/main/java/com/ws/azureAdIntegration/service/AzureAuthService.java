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
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
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
    final AzureSyncService azureSyncService;
    final AzureResourceSyncService azureResourceSyncService;
    final AzureAuthUtil azureAuthUtil;
    final AzureUserEntityService azureUserEntityService;
    final AzureUserRepository azureUserRepository;
    @Value("${spring.cloud.azure.active-directory.redirect-uri}")
    String redirectUri;

    @Autowired
    public AzureAuthService(AzureUserCredentialRepository azureUserCredentialRepository, BackendApplicationLogservice backendApplicationLogservice, AzureSyncService azureSyncService,
                            AzureResourceSyncService azureResourceSyncService, AzureAuthUtil azureAuthUtil, AzureUserEntityService azureUserEntityService, AzureUserRepository azureUserRepository) {
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureSyncService = azureSyncService;
        this.azureResourceSyncService = azureResourceSyncService;
        this.azureAuthUtil = azureAuthUtil;
        this.azureUserEntityService = azureUserEntityService;
        this.azureUserRepository = azureUserRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map creatAzureConfiguration(CreateAzureConfiguration createAzureConfiguration) {
        String subscriptionId = Optional.ofNullable(createAzureConfiguration.getSubscriptionId()).filter(subId -> !subId.isEmpty()).map(String::trim).orElse(null);
        String wsTenantName = createAzureConfiguration.getWsTenantName().trim();
        String clientId = createAzureConfiguration.getClientId().trim();
        String tenantId = createAzureConfiguration.getTenantId().trim();
        String clientSecret = Optional.ofNullable(createAzureConfiguration.getClientSecret())
                .map(secret -> {
                    try {
                        return EncryptionUtil.encrypt(secret.trim());
                    } catch (Exception e) {
                        log.error("Encryption error: ", e.getMessage());
                        throw new RuntimeException("Failed to encrypt client secret");
                    }
                })
                .orElseThrow(() -> new RuntimeException("Client secret found as null"));

        log.info("Validating user's Azure-AD credentials..");
        GraphServiceClient graphClient = azureAuthUtil.validateAzureCredentials(tenantId, clientId, createAzureConfiguration.getClientSecret());
        Optional.ofNullable(getAzureUserCredentialForWSTenant(wsTenantName))
                .ifPresent(credential -> {
                    throw new RuntimeException("Azure credentials already saved!");
                });
        AzureUserCredential azureUserCredential = azureUserCredentialRepository.save(AzureUserCredential.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .wsTenantName(wsTenantName)
                .createdAt(new Date())
                .build());
        backendApplicationLogservice.saveAuditLog(wsTenantName, "dummy@gmail.com", Constant.ADD, Constant.AZURE_CREDENTIALS_SAVED, "Info");
        azureSyncService.syncAzureData(wsTenantName, graphClient, azureUserCredential);
        return Collections.singletonMap("message", "Credentials configured successfully and Data sync started!");
    }


    public AzureUserCredential fetchAzureConfiguration(String tenantName) {
        AzureUserCredential azureUserCredential = Optional.ofNullable(getAzureUserCredentialForWSTenant(tenantName))
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found!"));

        azureUserCredential.setClientSecret(
                Optional.ofNullable(azureUserCredential.getClientSecret())
                        .map(secret -> {
                            try {
                                return EncryptionUtil.decrypt(secret);
                            } catch (Exception e) {
                                log.error("Decryption error: ", e.getMessage());
                                throw new RuntimeException("Failed to decrypt client secret");
                            }
                        })
                        .orElse(null)
        );
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
