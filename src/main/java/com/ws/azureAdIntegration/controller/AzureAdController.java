package com.ws.azureAdIntegration.controller;

import com.ws.azureAdIntegration.entity.AzureDevice;
import com.ws.azureAdIntegration.entity.AzureGroup;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.service.AzureAdService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/azure")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureAdController {
    final AzureAdService azureAdService;

    @Autowired
    public AzureAdController(AzureAdService azureAdService) {
        this.azureAdService = azureAdService;
    }

    @GetMapping("/sync")
    public void syncAzureWsData() {
        azureAdService.syncAzureData(1, "00b1d06b-e316-45af-a6d2-2734f62a5acd", "9acacaf6-02e1-4e06-84d9-5da4a7ffd2aa", "sJB8Q~G-YDCgTRPv6J~LZCQkNyDyUATwQvP_Bcx0");
    }
}

