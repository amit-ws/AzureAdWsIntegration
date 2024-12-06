package com.ws.azureAdIntegration.service;

import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
public class AzureUserCredentialService {
    final AzureUserCredentialRepository azureUserCredentialRepository;

    @Autowired
    public AzureUserCredentialService(AzureUserCredentialRepository azureUserCredentialRepository) {
        this.azureUserCredentialRepository = azureUserCredentialRepository;
    }

    protected AzureUserCredential findWSTeanantIdWithDecryptedSecret(String wsTenantName) {
        AzureUserCredential azureUserCredential = Optional.ofNullable(findWSTeanantIdWithoutDecryptedSecret(wsTenantName))
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
                        .orElseThrow(() -> new RuntimeException("Decrypted cClient secret found to be null")));
        return azureUserCredential;
    }

    protected AzureUserCredential findWSTeanantIdWithoutDecryptedSecret(String wsTenantName) {
        return azureUserCredentialRepository.findByWsTenantName(wsTenantName).orElse(null);
    }
}
