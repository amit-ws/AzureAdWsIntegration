package com.ws.azureAdIntegration.service;

import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureTenantRepository;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureResourcesIntegration.service.AzureResourceSyncService;
import lombok.AccessLevel;
import lombok.Synchronized;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Optional;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureSyncControlService {
    final AzureADSyncService azureADSyncService;
    final AzureResourceSyncService azureResourceSyncService;
    final AzureUserCredentialRepository azureUserCredentialRepository;
    final AzureTenantRepository azureTenantRepository;
    final AzureUserCredentialService azureUserCredentialService;
    final BackendApplicationLogservice backendApplicationLogservice;

    @Autowired
    public AzureSyncControlService(AzureADSyncService azureADSyncService, AzureResourceSyncService azureResourceSyncService, AzureUserCredentialRepository azureUserCredentialRepository, AzureTenantRepository azureTenantRepository, AzureUserCredentialService azureUserCredentialService, BackendApplicationLogservice backendApplicationLogservice) {
        this.azureADSyncService = azureADSyncService;
        this.azureResourceSyncService = azureResourceSyncService;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.azureTenantRepository = azureTenantRepository;
        this.azureUserCredentialService = azureUserCredentialService;
        this.backendApplicationLogservice = backendApplicationLogservice;
    }

    @Async
    @Transactional
    protected void syncAzureData(GraphServiceClient<Request> graphClient, AzureUserCredentialDTO azureUserCredentialDTO) {
        log.info("Thread name for syncAzureData: {}", Thread.currentThread().getName());
        try {
            AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, graphClient);
            azureADSyncService.syncAzureADData(azureTenant);
            Optional.ofNullable(azureUserCredentialDTO.getSubscriptionId())
                    .filter(StringUtils::isNotEmpty)
                    .ifPresentOrElse(subscriptionId -> azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredentialDTO),
                            () -> backendApplicationLogservice.saveAuditLog(
                                    azureUserCredentialDTO.getWsTenantName(), "demo@gmail.com", "ADD", Constant.AZURE_RESOURCE_DATA_SYNC_SKIPPED, "Info"));
        } catch (Exception exp) {
            log.error(String.format("Failure in %s thread while fetching azure data asynchronously", Thread.currentThread().getName()));
        }
        azureUserCredentialRepository.updateSyncStatusData(false, azureUserCredentialDTO.getId());
    }


    /* Sync Azure-Resources data */
    @Async
    @Transactional
    protected void syncAzureResourcesData(AzureUserCredentialDTO azureUserCredentialDTO) {
        log.info("Thread name for syncAzureResourcesData: {}", Thread.currentThread().getName());
        try {
            AzureTenant azureTenant = findAzureTenantOrSync(azureUserCredentialDTO);
            azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredentialDTO);
        } catch (Exception exp) {
            log.error(String.format("Failure in %s thread while fetching azure data asynchronously", Thread.currentThread().getName()));
        }
        azureUserCredentialRepository.updateSyncStatusData(false, azureUserCredentialDTO.getId());
    }

    /* on demand sync */
    @Async
    @Transactional
    public void startOnDemandSync(AzureUserCredentialDTO azureUserCredentialDTO) {
        log.info("Thread name for startOnDemandSync: {}", Thread.currentThread().getName());
        try {
            AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, null);
            azureADSyncService.syncAzureADData(azureTenant);
            Optional.ofNullable(azureUserCredentialDTO.getSubscriptionId())
                    .filter(StringUtils::isNotEmpty)
                    .ifPresentOrElse(subscriptionId -> azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredentialDTO),
                            () -> backendApplicationLogservice.saveAuditLog(
                                    azureUserCredentialDTO.getWsTenantName(), "demo@gmail.com", "ADD", Constant.AZURE_RESOURCE_DATA_SYNC_SKIPPED, "Info"));
        } catch (Exception exp) {
            log.error(String.format("Failure in %s thread while fetching azure data asynchronously", Thread.currentThread().getName()));
        }
        azureUserCredentialRepository.updateSyncStatusData(false, azureUserCredentialDTO.getId());
    }


    /* Sync only the Role Assignment from Azure for the Tenant*/
    @Transactional
    public void syncAzureRoleAssignments(String wsTenantName) {
        AzureUserCredentialDTO azureUserCredentialDTO = azureUserCredentialService.findWSTenantIdWithDecryptedSecret(wsTenantName);
        AzureTenant azureTenant = findAzureTenantOrSync(azureUserCredentialDTO);
        azureResourceSyncService.syncAzureRoleAssignmentData(azureTenant, azureUserCredentialDTO);
    }

    private AzureTenant findAzureTenantOrSync(AzureUserCredentialDTO azureUserCredentialDTO) {
        return azureTenantRepository
                .findByWsTenantName(azureUserCredentialDTO.getWsTenantName())
                .orElseGet(() -> azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, null));
    }


    //    @Transactional
//    public void syncAzureDataOnDemand(String wsTenantName) {
//        log.info("inside syncAzureDataOnDemand");
//        log.info("Thread name for syncAzureResourcesData: {}", Thread.currentThread().getName());
//        AzureUserCredential azureUserCredential = azureUserCredentialService.findByWSTenantName(wsTenantName);
//        if (azureUserCredential.isSyncStatus()) {
//            throw new RuntimeException("Sync is already in progress. It may take some time depending on your data size");
//        }
//        azureUserCredential.setSyncStatus(true);
//        azureUserCredential.setUpdatedAt(new Date());
//        azureUserCredentialRepository.saveAndFlush(azureUserCredential);
//        log.info("done");
//        startOnDemandSync(azureUserCredential);
//    }
}
