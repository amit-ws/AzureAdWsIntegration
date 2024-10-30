package com.ws.azureAdIntegration.service;

import com.ws.azureAdIntegration.dto.CreateAzureConfiguration;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.service.AzureADSyncService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureAuthService {
    final Logger log = LoggerFactory.getLogger(this.getClass());
    final AzureUserCredentialRepository azureUserCredentialRepository;
    final AzureADSyncService azureADSyncService;

    @Autowired
    public AzureAuthService(AzureUserCredentialRepository azureUserCredentialRepository, AzureADSyncService azureADSyncService) {
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.azureADSyncService = azureADSyncService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map creatAzureConfiguration(CreateAzureConfiguration createAzureConfiguration) {
        String clientId = createAzureConfiguration.getClientId().trim();
        String tenantId = createAzureConfiguration.getTenantId();
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
        String objectId = createAzureConfiguration.getObjectId().trim();
//        String wsTenantEmail = payload.get("ws_tenant_email");
//        Long wsTenantId = tenantAdminRepository.findByEmail(wsTenantEmail)
//                .filter(tenantAdmin -> tenantAdmin.equals(Role.SYS_ADMIN))
//                .map(TenantAdmin::getId)
//                .orElseThrow(() -> new RuntimeException(String.format("Only %s can configure Azure AD", Role.SYS_ADMIN)));

        Integer wsTenantId = 1;

        if (Optional.ofNullable(getAzureUserCredentialForWSTenant(wsTenantId)).isPresent()) {
            throw new RuntimeException("Azure credentials already saved!");
        }

        AzureUserCredential azureUserCredential = AzureUserCredential.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .objectId(objectId)
                .wsTenantId(wsTenantId)
                .createdAt(new Date())
                .build();
        azureUserCredentialRepository.save(azureUserCredential);
        azureADSyncService.syncAzureData(wsTenantId, tenantId, clientId, createAzureConfiguration.getClientSecret());
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


    private AzureUserCredential getAzureUserCredentialForWSTenant(Integer wsTenantId) {
        return azureUserCredentialRepository.findByWsTenantId(wsTenantId).orElse(null);
    }
}
