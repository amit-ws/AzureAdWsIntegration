package com.ws.azureAdIntegration.controller;

import com.ws.azureAdIntegration.service.AzureUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/azure/v1")
public class AzureUserController {
    final AzureUserService azureUserService;

    @Autowired
    public AzureUserController(AzureUserService azureUserService) {
        this.azureUserService = azureUserService;
    }

    @GetMapping("/users")
    public ResponseEntity fetchUsersHandlerHandler(@RequestParam("email") String email) {
        return ResponseEntity.ok(azureUserService.fetchUsers(email));
    }

    @GetMapping("/groups")
    public ResponseEntity fetchGroupsHandler(@RequestParam("email") String email) {
        return ResponseEntity.ok(azureUserService.fetchGroups(email));
    }

    @GetMapping("/applications")
    public ResponseEntity fetchApplicationsHandler(@RequestParam("email") String email) {
        return ResponseEntity.ok(azureUserService.fetchApplications(email));
    }

    @GetMapping("/appRoles")
    public ResponseEntity getAppRolesForApplication(@RequestParam("appId") Integer applicationId) {
        return ResponseEntity.ok(azureUserService.getAppRolesForApplication(applicationId));
    }

    @GetMapping("/devices")
    public ResponseEntity fetchAzureDevicesHandler(@RequestParam("email") String email) {
        return ResponseEntity.ok(azureUserService.fetchAzureDevices(email));
    }

    @GetMapping("/tenant")
    public ResponseEntity fetchTenantHandler(@RequestParam("email") String email) {
        return ResponseEntity.ok(azureUserService.getAzureTenantUsingwsTenantEmail(email));
    }


    @GetMapping("/group-users")
    public ResponseEntity fetchUsersOfGroupHandler(@RequestParam("groupId") Integer groupId) {
        return ResponseEntity.ok(azureUserService.fetchUsersOfGroup(groupId));
    }

    @GetMapping("/users-group")
    public ResponseEntity fetchGroupsOfUserHandler(@RequestParam("userId") Integer userId) {
        return ResponseEntity.ok(azureUserService.fetchGroupsOfUser(userId));
    }

    @GetMapping("/user-devices")
    public ResponseEntity fetchAzureDevicesForUserHandler(@RequestParam("userId") Integer userId) {
        return ResponseEntity.ok(azureUserService.fetchAzureDevicesForUser(userId));
    }

    @DeleteMapping("/tenant")
    public ResponseEntity deleteTenantHandler(@RequestParam("tenantId") String tenantId) {
        azureUserService.deleteTenant(tenantId);
        return ResponseEntity.noContent().build();
    }


}
