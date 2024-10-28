//package com.ws.azureAdIntegration.controller.fromWsBackend.azure;
//
//
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//@RequestMapping("/api/azure/v1")
//public class AzureUserService {
//    final AzureUserService userService;
//
//    @Autowired
//    public AzureUserService(AzureUserService userService) {
//        this.userService = userService;
//    }
//
//    @GetMapping("/users")
//    public ResponseEntity fetchUsersHandlerHandler() {
//        return ResponseEntity.ok(userService.fetchUsers());
//    }
//
//    @GetMapping("/groups")
//    public ResponseEntity fetchGroupsHandler() {
//        return ResponseEntity.ok(userService.fetchGroups());
//    }
//
//    @GetMapping("/applications")
//    public ResponseEntity fetchApplicationsHandler() {
//        return ResponseEntity.ok(userService.fetchApplications());
//    }
//
//    @GetMapping("/tenant")
//    public ResponseEntity fetchTenantHandler(@RequestParam String tenantId) {
//        return ResponseEntity.ok(userService.fetchTenant(tenantId));
//    }
//
//    @GetMapping("/groupMembers")
//    public ResponseEntity fetchUserGroupMembershipHandler() {
//        return ResponseEntity.ok(userService.fetchUserGroupMembership());
//    }
//
//    @GetMapping("/appRoles")
//    public ResponseEntity fetchAppRolesHandler() {
//        return ResponseEntity.ok(userService.fetchAppRoles());
//    }
//
//    @GetMapping("/azureAdDevices")
//    public ResponseEntity fetchAzureAdDevicesHandler() {
//        return ResponseEntity.ok(userService.fetchAzureAdDevices());
//    }
//
//    @GetMapping("/userDevices")
//    public ResponseEntity fetchAzureUserDeviceMappingHandler() {
//        return ResponseEntity.ok(userService.fetchAzureUserDeviceMapping());
//    }
//
//}
