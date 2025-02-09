package com.ws.azureKuberntesJIT.controller;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.constant.K8ResourceType;
import com.ws.azureKuberntesJIT.constant.RoleLevelType;
import com.ws.azureKuberntesJIT.response.K8RoleResponse;
import com.ws.azureKuberntesJIT.service.K8ResourceService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/k8-resources/")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8ResourceController {
    final K8ResourceService k8ResourceService;

    @Autowired
    public K8ResourceController(K8ResourceService k8ResourceService) {
        this.k8ResourceService = k8ResourceService;
    }

    @GetMapping("v1/get")
    public ResponseEntity<List<?>> getK8ResourcesHandler(@RequestParam("tenantName") String wsTenantName,
                                                         @RequestParam("cloud") CloudProviderType cloudProviderType,
                                                         @RequestParam(name = "type") K8ResourceType type) {
        return ResponseEntity.ok(k8ResourceService.getK8Resources(wsTenantName.trim(), cloudProviderType, type));
    }


    @GetMapping("v1/roles")
    public ResponseEntity<List<K8RoleResponse>> getK8RolesHandler(@RequestParam("tenantName") String wsTenantName,
                                                                  @RequestParam("cloud") CloudProviderType cloudProviderType,
                                                                  @RequestParam("roleType") RoleLevelType roleLevelType) {
        return ResponseEntity.ok(k8ResourceService.getK8Roles(wsTenantName.trim(), cloudProviderType, roleLevelType));
    }


    @GetMapping("v1/roles/byUid")
    public ResponseEntity<K8RoleResponse> getK8RoleByUIDHandler(@RequestParam("uid") String roleUID,
                                                                @RequestParam("tenantName") String wsTenantName,
                                                                @RequestParam("roleType") RoleLevelType roleLevelType) {
        return ResponseEntity.ok(k8ResourceService.getK8RoleByUID(roleUID.trim(), wsTenantName, roleLevelType));
    }
}
