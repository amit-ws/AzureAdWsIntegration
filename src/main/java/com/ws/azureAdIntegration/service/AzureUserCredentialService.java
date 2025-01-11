package com.ws.azureAdIntegration.service;

import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.mapper.AzureEntitiesMapper;
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

    public AzureUserCredentialDTO findWSTenantIdWithDecryptedSecret(String wsTenantName) {
        return mapFromAzureUserCredentialAndDecryptSecretKey(azureUserCredentialRepository.findByWsTenantName(wsTenantName)
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found for tenant: " + wsTenantName)));
    }

    public AzureUserCredentialDTO mapFromAzureUserCredentialAndDecryptSecretKey(AzureUserCredential azureUserCredential) {
        AzureUserCredentialDTO azureUserCredentialDTO = AzureEntitiesMapper.INSTANCE.fromAzureUserCredentialDTO(azureUserCredential);
        azureUserCredentialDTO.setClientSecret(EncryptionUtil.getDecryptedKey(azureUserCredentialDTO.getClientSecret(), Constant.AZURE_CLIENT_SECRET));
        return azureUserCredentialDTO;
    }
}
