package com.ws.azureResourcesIntegration.controller;

import com.ws.azureResourcesIntegration.entities.AzureServer;
import com.ws.azureResourcesIntegration.entities.AzureStorageAccount;
import com.ws.azureResourcesIntegration.entities.AzureVM;
import com.ws.azureResourcesIntegration.service.AzureResourceService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/azureResources")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceServiceController {
    final AzureResourceService azureResourceService;

    @Autowired
    public AzureResourceServiceController(AzureResourceService azureResourceService) {
        this.azureResourceService = azureResourceService;
    }

    @GetMapping("/v1/getAllVirtualMachines")
    public ResponseEntity<List<AzureVM>> getAllVirtualMachinesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getAllVirtualMachines(wsTenantName));
    }

    @GetMapping("/v1/getStorages")
    public ResponseEntity<List<AzureStorageAccount>> getStoragesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getStorages(wsTenantName));
    }

    @GetMapping("/v1/getServersWithDatabases")
    public ResponseEntity<List<AzureServer>> getServersWithDatavsesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getServersWithDatavses(wsTenantName));
    }
}
