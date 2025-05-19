package com.ws.azureAdIntegration.service;

import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.AzureAuthenticationCredentialDTO;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.azureAdIntegration.repository.AzureTenantRepository;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.mapper.AzureEntitiesMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Date;
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
        return mapFromAzureUserCredentialAndDecryptSecretKey(findByWSTenantName(wsTenantName));
    }

    public AzureAuthenticationCredentialDTO findAuthCredentialWithDecryptedSecret(String wsTenantName) {
        AzureAuthenticationCredentialDTO authCredential = findAuthenticationCredentialByWSTenantName(wsTenantName);
        authCredential.setClientSecret(EncryptionUtil.getDecryptedKey(authCredential.getClientSecret(), Constant.AZURE_CLIENT_SECRET));
        return authCredential;
    }

    public AzureUserCredentialDTO mapFromAzureUserCredentialAndDecryptSecretKey(AzureUserCredential azureUserCredential) {
        AzureUserCredentialDTO azureUserCredentialDTO = AzureEntitiesMapper.INSTANCE.fromAzureUserCredentialDTO(azureUserCredential);
        azureUserCredentialDTO.setClientSecret(EncryptionUtil.getDecryptedKey(azureUserCredentialDTO.getClientSecret(), Constant.AZURE_CLIENT_SECRET));
        if (!CollectionUtils.isEmpty(azureUserCredential.getSubscriptionIds())) {
            azureUserCredentialDTO.setSubscriptionIds(azureUserCredential.getSubscriptionIds());
        }
        return azureUserCredentialDTO;
    }

    public AzureUserCredential findByWSTenantName(String wsTenantName) {
        return azureUserCredentialRepository.findByWsTenantName(wsTenantName)
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found for tenant: " + wsTenantName));
    }

    public AzureAuthenticationCredentialDTO findAuthenticationCredentialByWSTenantName(String wsTenantName) {
        return azureUserCredentialRepository.findAzureUserCredentialUsingWsTenantName(wsTenantName)
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found for tenant: " + wsTenantName));
    }

    public AzureUserCredential updateAzureUserCredentialSyncStatus(String wsTenantName) {
        AzureUserCredential azureUserCredential = findByWSTenantName(wsTenantName);
        if (azureUserCredential.isSyncStatus()) {
            throw new RuntimeException("Please wait! Sync is already in progress. It may take some time depending on your data size");
        }
        azureUserCredential.setSyncStatus(true);
        azureUserCredential.setUpdatedAt(new Date());
        return azureUserCredentialRepository.saveAndFlush(azureUserCredential);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateSyncStatusData(boolean status, Integer id) {
        azureUserCredentialRepository.updateSyncStatusData(status, id);
    }

    public boolean checkIfCredentialExistsUsingTenantId(String tenantId) {
        return azureUserCredentialRepository.findByTenantId(tenantId).isPresent();
    }

    public AzureUserCredential getAzureUserCredential(Integer credId, String wsTenantName) {
        return azureUserCredentialRepository.findByIdAndWsTenantName(credId, wsTenantName)
                .orElseThrow(() -> new RuntimeException("No azure credentials found with provided data"));
    }

    public AzureAuthenticationCredentialDTO findAuthenticationCredentialByWSTenantName(Integer credId, String wsTenantName) {
        return azureUserCredentialRepository.findAzureUserCredentialUsingCredIdAndWsTenantName(credId, wsTenantName)
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found for tenant: " + wsTenantName));
    }
    public boolean checkIfSyncInProcess(String wsTenantName) {
        return azureUserCredentialRepository.checkSyncStatus(wsTenantName)
                .orElseThrow(() -> new AzureDataException("No Azure AD configuration found for tenant: " + wsTenantName));
    }

}
