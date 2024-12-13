package com.ws.azureAdIntegration.controller;

import com.ws.azureAdIntegration.service.AzureSyncService;
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

    final AzureSyncService azureSyncService;

    @Autowired
    public AzureADSyncController(AzureSyncService azureSyncService) {
        this.azureSyncService = azureSyncService;
    }

    @GetMapping("onDemand")
    public ResponseEntity syncAzureADData(@RequestParam String email) {
        azureSyncService.syncAzureData(email);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(Collections.singletonMap("message", "Data synced successfully!"));
    }
}
