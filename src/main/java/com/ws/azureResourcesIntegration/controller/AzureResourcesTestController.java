package com.ws.azureResourcesIntegration.controller;

import com.ws.azureResourcesIntegration.service.AzureResourcesTestService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/azureResources")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourcesTestController {
    final AzureResourcesTestService azureResourcesTestService;

    @Autowired
    public AzureResourcesTestController(AzureResourcesTestService azureResourcesTestService) {
        this.azureResourcesTestService = azureResourcesTestService;
    }

    @GetMapping("/getAllVMs")
    public ResponseEntity listVMsHandler() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureResourcesTestService.listVMs());
    }

    @GetMapping("/getResourceGroups")
    public ResponseEntity listResourceGroupsHandler() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureResourcesTestService.listResourceGroups());
    }

    @GetMapping("/getStorageAccounts")
    public ResponseEntity listStorageAccountsHandler() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureResourcesTestService.listStorageAccounts());
    }

    @GetMapping("/getRBACRoles")
    public ResponseEntity listRBACRolesHandler() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureResourcesTestService.listRBACRoles());
    }

    @GetMapping("/getRBACRolesAssignment")
    public ResponseEntity listRBACRolesAssignmentHandler() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureResourcesTestService.listRoleAssignments());
    }

    @GetMapping("/getAllServersAndDatabases")
    public ResponseEntity getServersAndDBSHandler() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureResourcesTestService.listAllServerWithDBsForTenant());
    }

    @GetMapping("/getSubscriptions")
    public ResponseEntity listAllSubscriptionsHandler() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureResourcesTestService.listSubscriptions());
    }
}
