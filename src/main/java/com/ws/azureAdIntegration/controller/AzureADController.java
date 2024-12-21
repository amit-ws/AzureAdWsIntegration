package com.ws.azureAdIntegration.controller;

import com.ws.azureAdIntegration.service.AzureADService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/azure/v1")
public class AzureADController {
    final AzureADService azureADService;

    @Autowired
    public AzureADController(AzureADService azureADService) {
        this.azureADService = azureADService;
    }

    @GetMapping("/users")
    public ResponseEntity fetchUsersHandlerHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureADService.fetchUsers(wsTenantName));
    }

    @GetMapping("/groups")
    public ResponseEntity fetchGroupsHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureADService.fetchGroups(wsTenantName));
    }

    @GetMapping("/applications")
    public ResponseEntity fetchApplicationsHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureADService.fetchApplications(wsTenantName));
    }

    @GetMapping("/appRoles")
    public ResponseEntity getAppRolesForApplication(@RequestParam("appId") Integer applicationId) {
        return ResponseEntity.ok(azureADService.getAppRolesForApplication(applicationId));
    }

    @GetMapping("/devices")
    public ResponseEntity fetchAzureDevicesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureADService.fetchAzureDevices(wsTenantName));
    }

    @GetMapping("/tenant")
    public ResponseEntity fetchTenantHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureADService.getAzureTenantUsingwsTenantEmail(wsTenantName));
    }


    @GetMapping("/group-users")
    public ResponseEntity fetchUsersOfGroupHandler(@RequestParam("groupId") Integer groupId) {
        return ResponseEntity.ok(azureADService.fetchUsersOfGroup(groupId));
    }

    @GetMapping("/users-group")
    public ResponseEntity fetchGroupsOfUserHandler(@RequestParam("userId") Integer userId) {
        return ResponseEntity.ok(azureADService.fetchGroupsOfUser(userId));
    }

    @GetMapping("/user-devices")
    public ResponseEntity fetchAzureDevicesForUserHandler(@RequestParam("userId") Integer userId) {
        return ResponseEntity.ok(azureADService.fetchAzureDevicesForUser(userId));
    }

    @DeleteMapping("/tenant")
    public ResponseEntity deleteTenantHandler(@RequestParam("tenantId") String tenantId) {
        azureADService.deleteTenant(tenantId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/getRolesAndGroups")
    public ResponseEntity getUserDetailsWithGroupNamesAndRoleNamesUsingTenantNameHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureADService.getUserDetailsWithGroupNamesAndRoleNamesUsingTenantName(wsTenantName));
    }

    @GetMapping("/users/getByAzureId")
    public ResponseEntity getAzureUserByIdHandler(@RequestParam("azureId") String azureUserId) {
        return ResponseEntity.ok(azureADService.getAzureUserById(azureUserId));
    }


}
