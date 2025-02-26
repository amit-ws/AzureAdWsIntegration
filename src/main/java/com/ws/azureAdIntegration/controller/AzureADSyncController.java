package com.ws.azureAdIntegration.controller;

import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.service.AzureSyncControlService;
import com.ws.azureAdIntegration.service.AzureUserCredentialService;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/azure-sync")
@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
public class AzureADSyncController {

    final AzureSyncControlService azureSyncControlService;
    final AzureUserCredentialService azureUserCredentialService;
    final BackendApplicationLogservice backendApplicationLogservice;

    @Autowired
    public AzureADSyncController(AzureSyncControlService azureSyncControlService, AzureUserCredentialService azureUserCredentialService, BackendApplicationLogservice backendApplicationLogservice) {
        this.azureSyncControlService = azureSyncControlService;
        this.azureUserCredentialService = azureUserCredentialService;
        this.backendApplicationLogservice = backendApplicationLogservice;
    }

    @GetMapping("onDemand")
    public ResponseEntity syncAzureADData(@RequestParam String tenantName) {
        log.info("Thread name for syncAzureADData: {}", Thread.currentThread().getName());
        AzureUserCredential azureUserCredential = azureUserCredentialService.updateAzureUserCredentialSyncStatus(tenantName.trim());
        azureSyncControlService.startOnDemandSync(azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(azureUserCredential));
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(Collections.singletonMap("message", "Sync started"));
    }


//    @GetMapping("/onDemand")
//    public ResponseEntity syncAzureADDataOnDemand(@RequestParam String tenantName) {
//        log.info("Thread name for syncAzureADData: {}", Thread.currentThread().getName());
//        AzureUserCredential azureUserCredential = azureUserCredentialService.updateAzureUserCredentialSyncStatus(tenantName.trim());
//        CompletableFuture<Void> syncFuture = azureSyncControlService.startOnDemandSync(azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(azureUserCredential));
//        try {
//            syncFuture.get(1, TimeUnit.MICROSECONDS);
//        } catch (TimeoutException e) {
//            log.error("Azure sync task timed out.");
//            backendApplicationLogservice.saveAuditLog(tenantName, tenantName, Constant.ADD, Constant.AZURE_SYNC_TIME_OUT, "Error");
//            azureUserCredentialService.updateSyncStatusData(false, azureUserCredential.getId());
//            return ResponseEntity
//                    .status(HttpStatus.REQUEST_TIMEOUT)
//                    .body(Collections.singletonMap("message", "Sync operation timed out"));
//        } catch (InterruptedException | ExecutionException e) {
//            log.error("Error occurred during Azure sync task: {}", e.getMessage());POI
//            backendApplicationLogservice.saveAuditLog(tenantName, tenantName, Constant.ADD, String.format(Constant.AZURE_SYNC_FAILURE, e.getMessage()), "Error");
//            azureUserCredentialService.updateSyncStatusData(false, azureUserCredential.getId());
//            return ResponseEntity
//                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(Collections.singletonMap("message", "Error during data sync."));
//        }
//        return ResponseEntity
//                .status(HttpStatus.ACCEPTED)
//                .body(Collections.singletonMap("message", "Data sync started"));
//    }

    @GetMapping("v1/onDemand/assignedRoles")
    public ResponseEntity syncAzureRoleAssignmentsHandler(@RequestParam String tenantName) {
        azureSyncControlService.syncAzureRoleAssignments(tenantName);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(Collections.singletonMap("message", "Azure role assignments synced successfully!"));
    }

    @GetMapping("v1/onDemand/k8Resources")
    public ResponseEntity syncKubernetesResourcesDataHandler(@RequestParam String tenantName) {
        AzureUserCredential azureUserCredential = azureUserCredentialService.updateAzureUserCredentialSyncStatus(tenantName.trim());
        azureSyncControlService.syncKubernetesResourcesData(azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(azureUserCredential));
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(Collections.singletonMap("message", "Kubernetes resources data synced successfully!"));
    }
}
