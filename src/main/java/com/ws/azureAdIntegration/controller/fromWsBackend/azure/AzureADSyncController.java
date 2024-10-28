package com.ws.azureAdIntegration.controller.fromWsBackend.azure;

import com.ws.service.AzureADSyncService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
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
public class AzureADSyncController {

    final AzureADSyncService azureADSyncService;

    @Autowired
    public AzureADSyncController(AzureADSyncService azureADSyncService) {
        this.azureADSyncService = azureADSyncService;
    }

    @GetMapping
    public ResponseEntity syncAzureADData(@RequestParam Integer wsTenantId) {
        azureADSyncService.syncAzureData(wsTenantId);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Collections.singletonMap("message", "Data synced successfully!"));
    }
}
