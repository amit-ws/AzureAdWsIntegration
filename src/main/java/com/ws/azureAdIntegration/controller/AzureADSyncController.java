package com.ws.azureAdIntegration.controller;

import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.service.AzureSyncControlService;
import com.ws.azureAdIntegration.service.AzureUserCredentialService;
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

@RestController
@RequestMapping("/api/azure-sync")
@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
public class AzureADSyncController {

    final AzureSyncControlService azureSyncControlService;
    final AzureUserCredentialService azureUserCredentialService;

    @Autowired
    public AzureADSyncController(AzureSyncControlService azureSyncControlService, AzureUserCredentialService azureUserCredentialService) {
        this.azureSyncControlService = azureSyncControlService;
        this.azureUserCredentialService = azureUserCredentialService;
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

    @GetMapping("v1/onDemand/assignedRoles")
    public ResponseEntity syncAzureRoleAssignmentsHandler(@RequestParam String tenantName) {
        azureSyncControlService.syncAzureRoleAssignments(tenantName);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(Collections.singletonMap("message", "Azure role assignments synced successfully!"));
    }
}
