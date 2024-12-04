package com.ws.azureResourcesIntegration.controller;

import com.ws.azureResourcesIntegration.service.AzureResourceFeatureService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/azureResources")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceFeatureController {
    final AzureResourceFeatureService azureResourceFeatureService;

    @Autowired
    public AzureResourceFeatureController(AzureResourceFeatureService azureResourceFeatureService) {
        this.azureResourceFeatureService = azureResourceFeatureService;
    }

    @GetMapping("/getSubscriptions")
    public void listAllSubscriptionsHandler(@RequestParam String tenantName) {
        azureResourceFeatureService.listAllSubscriptions(tenantName);
    }
}
