package com.ws.azureAdIntegration.service;

import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.repository.AzureTenantRepository;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureKuberntesJIT.dto.K8ResourceDataSyncRequest;
import com.ws.azureKuberntesJIT.service.K8ResourcesDataService;
import com.ws.azureKuberntesJIT.service.K8ResourcesSyncService;
import com.ws.azureResourcesIntegration.service.AzureResourceSyncService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureSyncControlService {
    final AzureADSyncService azureADSyncService;
    final AzureResourceSyncService azureResourceSyncService;
    final K8ResourcesSyncService k8ResourcesSyncService;
    final AzureUserCredentialRepository azureUserCredentialRepository;
    final AzureTenantRepository azureTenantRepository;
    final AzureUserCredentialService azureUserCredentialService;
    final K8ResourcesDataService k8ResourcesDataService;
    final BackendApplicationLogservice backendApplicationLogservice;

    @Autowired
    public AzureSyncControlService(AzureADSyncService azureADSyncService, AzureResourceSyncService azureResourceSyncService,
                                   K8ResourcesSyncService k8ResourcesSyncService, AzureUserCredentialRepository azureUserCredentialRepository,
                                   AzureTenantRepository azureTenantRepository, AzureUserCredentialService azureUserCredentialService,
                                   K8ResourcesDataService k8ResourcesDataService, BackendApplicationLogservice backendApplicationLogservice) {
        this.azureADSyncService = azureADSyncService;
        this.azureResourceSyncService = azureResourceSyncService;
        this.k8ResourcesSyncService = k8ResourcesSyncService;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.azureTenantRepository = azureTenantRepository;
        this.azureUserCredentialService = azureUserCredentialService;
        this.k8ResourcesDataService = k8ResourcesDataService;
        this.backendApplicationLogservice = backendApplicationLogservice;
    }

    @Async
    @Transactional
    protected void syncAzureData(GraphServiceClient<Request> graphClient, AzureUserCredentialDTO azureUserCredentialDTO) {
        log.info("Thread name for syncAzureData: {}", Thread.currentThread().getName());
        try {
            AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, graphClient);
            azureADSyncService.syncAzureADData(azureTenant);
            syncAzureResourcesDataIfSubscriptionsExist(azureTenant, azureUserCredentialDTO);
        } catch (Exception exp) {
            log.error(String.format("Failure in %s thread while fetching azure data asynchronously", Thread.currentThread().getName()));
        }
        azureUserCredentialService.updateSyncStatusData(false, azureUserCredentialDTO.getId());
    }


    /* Sync Azure-Resources data */
    @Async
    @Transactional
    protected void syncAzureResourcesData(AzureUserCredentialDTO azureUserCredentialDTO) {
        log.info("Thread name for syncAzureResourcesData: {}", Thread.currentThread().getName());
        try {
            AzureTenant azureTenant = findOrSyncAzureTenant(azureUserCredentialDTO);
            syncAzureResourcesDataIfSubscriptionsExist(azureTenant, azureUserCredentialDTO);
        } catch (Exception exp) {
            log.error(String.format("Failure in %s thread while fetching azure data asynchronously", Thread.currentThread().getName()));
        }
        azureUserCredentialService.updateSyncStatusData(false, azureUserCredentialDTO.getId());
    }


    /* on demand sync */
    @Async
    @Transactional
    public void startOnDemandSync(AzureUserCredentialDTO azureUserCredentialDTO) {
        log.info("Thread name for startOnDemandSync: {}", Thread.currentThread().getName());
        try {
            AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, null);
            azureADSyncService.syncAzureADData(azureTenant);
            syncAzureResourcesDataIfSubscriptionsExist(azureTenant, azureUserCredentialDTO);
        } catch (Exception exp) {
            log.error(String.format("Failure in %s thread while fetching azure data asynchronously", Thread.currentThread().getName()));
        }
        azureUserCredentialService.updateSyncStatusData(false, azureUserCredentialDTO.getId());
    }


    private void syncAzureResourcesDataIfSubscriptionsExist(AzureTenant azureTenant, AzureUserCredentialDTO azureUserCredentialDTO) {
        if (CollectionUtils.isNotEmpty(azureUserCredentialDTO.getSubscriptionIds())) {
            azureResourceSyncService.syncAzureResourcesData(azureTenant, azureUserCredentialDTO, false);
        } else {
            log.warn("No subscription ID(s) found for the tenant: {}. Skipping Azure resources data sync.", azureTenant.getWsTenantName());
            backendApplicationLogservice.saveAuditLog(azureTenant.getWsTenantName(), "demo@gmail.com", "ADD",
                    Constant.AZURE_RESOURCE_DATA_SYNC_SKIPPED, "Info");
        }
    }


    /* Sync only the Role Assignment from Azure for the Tenant*/
    @Transactional
    public void syncAzureRoleAssignments(String wsTenantName) {
        AzureUserCredentialDTO azureUserCredentialDTO = azureUserCredentialService.findWSTenantIdWithDecryptedSecret(wsTenantName);
        AzureTenant azureTenant = findOrSyncAzureTenant(azureUserCredentialDTO);
        azureResourceSyncService.syncAzureResourcesData(azureTenant, azureUserCredentialDTO, true);
    }


    /* Sync only the k8 RESOURCES data for the Tenant*/
    @Transactional
    public void syncKubernetesResourcesData(AzureUserCredentialDTO azureUserCredentialDTO) {
        try {
            if (CollectionUtils.isNotEmpty(azureUserCredentialDTO.getSubscriptionIds())) {
                AzureTenant azureTenant = findOrSyncAzureTenant(azureUserCredentialDTO);
                azureResourceSyncService.syncK8ResourcesData(azureTenant.getWsTenantName(), azureUserCredentialDTO);
            }
        } catch (Exception ignored) {
            log.error("Error in syncing K8 resources data. Caller: syncKubernetesResourcesData");
            log.error("Error: {}", ignored.getMessage());
        }
        azureUserCredentialService.updateSyncStatusData(false, azureUserCredentialDTO.getId());
    }

    private AzureTenant findOrSyncAzureTenant(AzureUserCredentialDTO azureUserCredentialDTO) {
        return azureTenantRepository
                .findByWsTenantName(azureUserCredentialDTO.getWsTenantName())
                .orElseGet(() -> azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, null));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncK8RolesAndBindings(K8ResourceDataSyncRequest syncRequest) {
        log.info("Async process started.......");
        k8ResourcesDataService.deleteK8RolesAndBindings(syncRequest.getWsTenantName());
        k8ResourcesSyncService.executeSync(syncRequest);
    }


//    @Async
//    @Transactional
//    public CompletableFuture<Void> startOnDemandSync(AzureUserCredentialDTO azureUserCredentialDTO) {
//        String threadName = Thread.currentThread().getName();
//        log.info("Thread name for startOnDemandSync: {}", threadName);
//        try {
//            AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, null);
//            azureADSyncService.syncAzureADData(azureTenant);
//            Optional.ofNullable(azureUserCredentialDTO.getSubscriptionId())
//                    .filter(StringUtils::isNotEmpty)
//                    .ifPresentOrElse(subscriptionId -> azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredentialDTO),
//                            () -> backendApplicationLogservice.saveAuditLog(
//                                    azureUserCredentialDTO.getWsTenantName(), "demo@gmail.com", Constant.ADD, Constant.AZURE_RESOURCE_DATA_SYNC_SKIPPED, "Info"));
//        } catch (Exception exp) {
//            log.error(String.format("Failure in %s thread while fetching azure data asynchronously", threadName));
//        }
//        azureUserCredentialRepository.updateSyncStatusData(false, azureUserCredentialDTO.getId());
//        return CompletableFuture.completedFuture(null);
//    }

//    @Async
//    public void startOnDemandSync(AzureUserCredentialDTO azureUserCredentialDTO) {
//        String threadName = Thread.currentThread().getName();
//        log.info("Thread name for startOnDemandSync: {}", threadName);
//
//        // Set up a timeout for this task (e.g., 1 minute)
//        long timeoutMillis = 2000; // 60 seconds
//        ExecutorService executorService = Executors.newSingleThreadExecutor();
//        Callable<Void> task = () -> {
//            try {
//                AzureTenant azureTenant = azureADSyncService.initializeGraphClientAndSyncAzureTenant(azureUserCredentialDTO, null);
//                log.info("tenant synced.....");
//                azureADSyncService.syncAzureADData(azureTenant);
//                log.info("syncAzureADData synced.....");
//
//                Optional.ofNullable(azureUserCredentialDTO.getSubscriptionId())
//                        .filter(StringUtils::isNotEmpty)
//                        .ifPresentOrElse(subscriptionId -> azureResourceSyncService.syncAzureResourceData(azureTenant, azureUserCredentialDTO),
//                                () -> backendApplicationLogservice.saveAuditLog(
//                                        azureUserCredentialDTO.getWsTenantName(), "demo@gmail.com", Constant.ADD, Constant.AZURE_RESOURCE_DATA_SYNC_SKIPPED, "Info"));
//
//                return null;  // Task completed successfully
//            } catch (Exception exp) {
//                log.info("Inside exp..........");
//                throw exp;
//            }
//        };
//        // Execute the task with a timeout
//        Future<Void> future = executorService.submit(task);
//        try {
//            future.get(timeoutMillis, TimeUnit.MILLISECONDS); // Timeout after 60 seconds
//        } catch (TimeoutException e) {
//            log.error("Async task exceeded the timeout.");
//            future.cancel(true);
//            backendApplicationLogservice.saveAuditLog(azureUserCredentialDTO.getWsTenantName(), "demo@gmail.com", Constant.ADD, Constant.AZURE_SYNC_TIME_OUT, "Error");
//        } catch (InterruptedException | ExecutionException e) {
//            log.error("Error occurred during Azure sync task: {}", e.getMessage());
//            backendApplicationLogservice.saveAuditLog(azureUserCredentialDTO.getWsTenantName(), "demo@gmail.com", Constant.ADD, String.format(Constant.AZURE_SYNC_FAILURE, e.getMessage()), "Error");
//        } catch (Exception e) {
//            log.error("Error occurred during Azure sync task.", e);
//            backendApplicationLogservice.saveAuditLog(azureUserCredentialDTO.getWsTenantName(), "demo@gmail.com", Constant.ADD, String.format(Constant.AZURE_DATA_ASYNCHRONOUS_FAILURE, e.getMessage()), "Error");
//        } finally {
//            executorService.shutdown();
//        }
//        azureUserCredentialService.updateSyncStatusData(false, azureUserCredentialDTO.getId());
//    }


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
