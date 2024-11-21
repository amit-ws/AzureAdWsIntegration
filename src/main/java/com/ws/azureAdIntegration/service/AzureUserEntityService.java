package com.ws.azureAdIntegration.service;

import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AzureUserEntityService {
    final AzureUserRepository azureUserRepository;

    @Autowired
    public AzureUserEntityService(AzureUserRepository azureUserRepository) {
        this.azureUserRepository = azureUserRepository;
    }

    protected AzureUser getAzureUserUsingEmail(String email) {
        return azureUserRepository.findByUserPrincipalName(email)
                .map(azureUser -> {
                    if (!azureUser.getIsSSOEnabled()) {
                        throw new RuntimeException("Azure user not SSO enabled");
                    }
                    return azureUser;
                })
                .orElseThrow(() -> new RuntimeException(String.format("No Azure User found with provided email: %s", email)));
    }
}
