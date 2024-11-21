package com.ws.azureAdIntegration.service;

import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.CreateAzureConfiguration;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestParam;
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
    final AzureADSyncService azureADSyncService;
    final AzureAuthUtil azureAuthUtil;
    final AzureUserEntityService azureUserEntityService;
    @Value("${spring.cloud.azure.active-directory.redirect-uri}")
    String redirectUri;

    @Autowired
    public AzureAuthService(AzureUserCredentialRepository azureUserCredentialRepository, BackendApplicationLogservice backendApplicationLogservice, AzureADSyncService azureADSyncService, AzureAuthUtil azureAuthUtil, AzureUserEntityService azureUserEntityService) {
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureADSyncService = azureADSyncService;
        this.azureAuthUtil = azureAuthUtil;
        this.azureUserEntityService = azureUserEntityService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map creatAzureConfiguration(CreateAzureConfiguration createAzureConfiguration) {
        Integer wsTenantId = 1;
        String objectId = createAzureConfiguration.getObjectId().trim();
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
        GraphServiceClient graphClient = azureAuthUtil.validateAzureCredentialsWithGraphApi(tenantId, clientId, createAzureConfiguration.getClientSecret(), objectId);
        Optional.ofNullable(getAzureUserCredentialForWSTenant(wsTenantId))
                .ifPresent(credential -> {
                    throw new RuntimeException("Azure credentials already saved!");
                });

        AzureUserCredential azureUserCredential = AzureUserCredential.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .objectId(objectId)
                .wsTenantId(wsTenantId)
                .createdAt(new Date())
                .build();
        azureUserCredentialRepository.save(azureUserCredential);
        backendApplicationLogservice.saveAuditLog("ws-amit-tenant", wsTenantId, Constant.ADD, Constant.AZURE_CREDENTIALS_SAVED, "Info");
        azureADSyncService.syncAzureData(wsTenantId, graphClient, tenantId);
        return Collections.singletonMap("message", "Credentials configured successfully and Data sync started!");
    }


    public AzureUserCredential fetchAzureConfiguration(@RequestParam("email") String email) {
        Integer wsTenantId = 1;

        AzureUserCredential azureUserCredential = Optional.ofNullable(getAzureUserCredentialForWSTenant(wsTenantId))
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
        AzureUserCredential azureUserCredential = getAzureUserCredentialForWSTenant(1);

        String url = UriComponentsBuilder.fromHttpUrl("https://login.microsoftonline.com/")
                .pathSegment(azureUserCredential.getTenantId(), Constant.OAUTH, Constant.OAUTH_VERSION, Constant.OAUTH_TYPE)
                .queryParam(Constant.CLIENT_ID_PARAM, azureUserCredential.getClientId())
                .queryParam(Constant.RESPONSE_TYPE_PARAM, Constant.AZURE_RESPONSE_TYPE)
                .queryParam(Constant.REDIRECT_URI_PARAM, redirectUri)
                .queryParam(Constant.RESPONSE_MODE_PARAM, Constant.AZURE_RESPONSE_MODE)
                .queryParam(Constant.SCOPE_PARAM, URLEncoder.encode("offline_access User.Read Mail.Read", StandardCharsets.UTF_8))
                .toUriString();
        return url;
    }


    private AzureUserCredential getAzureUserCredentialForWSTenant(Integer wsTenantId) {
        return azureUserCredentialRepository.findByWsTenantId(wsTenantId).orElse(null);
    }
}
