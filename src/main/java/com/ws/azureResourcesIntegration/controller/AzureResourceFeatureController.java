package com.ws.azureResourcesIntegration.controller;

import com.ws.azureResourcesIntegration.service.AzureResourceFeatureService_OG;
import com.ws.azureResourcesIntegration.service.AzureResourcesService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/azure-resources")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceFeatureController {
    final AzureResourceFeatureService_OG azureResourceFeatureServiceOG;
    final AzureResourcesService azureResourcesService;

    @Autowired
    public AzureResourceFeatureController(AzureResourceFeatureService_OG azureResourceFeatureServiceOG, AzureResourcesService azureResourcesService) {
        this.azureResourceFeatureServiceOG = azureResourceFeatureServiceOG;
        this.azureResourcesService = azureResourcesService;
    }

    @GetMapping("/getSubscriptions")
    public void listAllSubscriptionsHandler(@RequestParam String tenantName) {
        azureResourceFeatureServiceOG.listAllSubscriptions(tenantName);
    }

    @GetMapping("/getAllVMs")
    public void listVMsHandler() {
        azureResourcesService.listVMs();
    }
}
