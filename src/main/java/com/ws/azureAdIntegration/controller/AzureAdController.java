//package com.ws.azureAdIntegration.controller;
//
//import com.ws.azureAdIntegration.entity.AzureDevice;
//import com.ws.azureAdIntegration.entity.AzureGroup;
//import com.ws.azureAdIntegration.entity.AzureUser;
//import com.ws.azureAdIntegration.service.AzureAdService;
//import lombok.AccessLevel;
//import lombok.experimental.FieldDefaults;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.util.List;
//
//@RestController
//@RequestMapping("/api/azure")
//@FieldDefaults(level = AccessLevel.PRIVATE)
//public class AzureAdController {
//    final AzureAdService azureAdService;
//
//    @Autowired
//    public AzureAdController(AzureAdService azureAdService) {
//        this.azureAdService = azureAdService;
//    }
//
//    @GetMapping("/sync")
//    public void syncAzureWsData() {
//        azureAdService.syncAzureData(1, "", "", "");
//    }
//}
//
