//package com.ws.azureAdIntegration.controller.fromWsBackend.azure;
//
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
//        List<AzureUser> users = azureAdService.syncUsersData();
//        List<AzureGroup> groups = azureAdService.syncGroupsData();
//        azureAdService.syncUsersGroupsMembershipData(users, groups);
//        List<AzureDevice> devices = azureAdService.syncDevicesData();
//        azureAdService.syncUsersDeviceRelationshipData(users, devices);
//        azureAdService.syncTenantData();
//        azureAdService.syncApplications();
//    }
//}
//
