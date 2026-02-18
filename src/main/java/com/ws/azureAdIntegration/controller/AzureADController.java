package com.ws.azureAdIntegration.controller;

import com.ws.azureAdIntegration.service.AzureADService;
import com.ws.azureAdIntegration.service.AzureTenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/azure")
public class AzureADController {
    final AzureADService azureADService;
    final AzureTenantService azureTenantService;

    @Autowired
    public AzureADController(AzureADService azureADService, AzureTenantService azureTenantService) {
        this.azureADService = azureADService;
        this.azureTenantService = azureTenantService;
    }

    @GetMapping("/v1/users")
    public ResponseEntity fetchUsersHandlerHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureADService.fetchUsers(wsTenantName));
    }

    @GetMapping("/v1/groups")
    public ResponseEntity fetchGroupsHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureADService.fetchGroups(wsTenantName));
    }

    @GetMapping("/v1/applications")
    public ResponseEntity fetchApplicationsHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureADService.fetchApplications(wsTenantName));
    }

    @GetMapping("/v1/appRoles")
    public ResponseEntity getAppRolesForApplication(@RequestParam("appId") Integer applicationId) {
        return ResponseEntity.ok(azureADService.getAppRolesForApplication(applicationId));
    }

    @GetMapping("/v1/devices")
    public ResponseEntity fetchAzureDevicesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureADService.fetchAzureDevices(wsTenantName));
    }

    @GetMapping("/v1/tenant")
    public ResponseEntity fetchTenantHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureTenantService.getAzureTenantUsingWsTenantName(wsTenantName));
    }


    @GetMapping("/v1/group-users")
    public ResponseEntity fetchUsersOfGroupHandler(@RequestParam("groupId") Integer groupId) {
        return ResponseEntity.ok(azureADService.fetchUsersOfGroup(groupId));
    }

    @GetMapping("/v1/users-group")
    public ResponseEntity fetchGroupsOfUserHandler(@RequestParam("userId") Integer userId) {
        return ResponseEntity.ok(azureADService.fetchGroupsOfUser(userId));
    }

    @GetMapping("/v1/user-devices")
    public ResponseEntity fetchAzureDevicesForUserHandler(@RequestParam("userId") Integer userId) {
        return ResponseEntity.ok(azureADService.fetchAzureDevicesForUser(userId));
    }

    @DeleteMapping("/v1/tenant")
    public ResponseEntity deleteTenantHandler(@RequestParam("tenantId") String tenantId) {
        azureADService.deleteTenant(tenantId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/v2/tenant")
    public ResponseEntity<Void> deleteTenantHandlerV2(@RequestParam("tenantName") String wsTenantName) {
        azureADService.deleteTenantV2(wsTenantName.trim());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/users/getRolesAndGroups")
    public ResponseEntity getUserDetailsWithGroupNamesAndRoleNamesUsingTenantNameHandler(@RequestParam("tenantName") String wsTenantName,
                                                                                         @RequestParam(value = "azureId", required = false) String azureId) {
        return ResponseEntity.ok(azureADService.getUserDetailsWithGroupNamesAndRoleNamesUsingTenantName(wsTenantName, azureId));
    }

    @GetMapping("/v1/users/getByAzureId")
    public ResponseEntity getAzureUserByIdHandler(@RequestParam("azureId") String azureUserId) {
        return ResponseEntity.ok(azureADService.getAzureUserById(azureUserId));
    }

    @GetMapping("/v1/users/getGroupNamesById/{userId}")
    public ResponseEntity getGroupNamesForUserHandler(@PathVariable("userId") Integer userId) {
        return ResponseEntity.ok(azureADService.getGroupNamesForUser(userId));
    }

    @DeleteMapping("/v1/groupMembership")
    public ResponseEntity<Void> removeUserGroupMembershipHandler(@RequestParam String userId, @RequestParam String groupId, @RequestParam(value = "tenantName") String wsTenantName) {
        azureADService.removeUserGroupMembership(userId.trim(), groupId.trim(), wsTenantName.trim());
        return ResponseEntity.ok().build();
    }

}
