package com.ws.azureKuberntesJIT.controller;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.constants.PublishResourceType;
import com.ws.azureAdIntegration.exception.K8ResourceException;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.constant.K8ResourceType;
import com.ws.azureKuberntesJIT.dto.K8ResourceRequest;
import com.ws.azureKuberntesJIT.dto.K8RolePolicyRuleDTO;
import com.ws.azureKuberntesJIT.response.K8RoleResponse;
import com.ws.azureKuberntesJIT.service.K8ResourceService;
import com.ws.azureResourcesIntegration.dto.PublishResourceRequest;
import com.ws.azureResourcesIntegration.service.PublishResourceService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/k8-resources/")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8ResourceController {
    final K8ResourceService k8ResourceService;
    final PublishResourceService publishResourceService;

    @Autowired
    public K8ResourceController(K8ResourceService k8ResourceService, PublishResourceService publishResourceService) {
        this.k8ResourceService = k8ResourceService;
        this.publishResourceService = publishResourceService;
    }

    @PostMapping("v1/get")
    public ResponseEntity<List<?>> getK8ResourcesHandler(@RequestParam("resourceLevel") K8ResourceLevel resourceLevel,
                                                         @Valid @RequestBody K8ResourceRequest request) {
        return ResponseEntity.ok(k8ResourceService.getK8Resources(request, resourceLevel));
    }

    @GetMapping("v1/get")
    public ResponseEntity<List<?>> getNamespaceLevelK8ResourcesHandler(@RequestParam("clusterId") String clusterId,
                                                                       @RequestParam("tenantName") String wsTenantName,
                                                                       @RequestParam("type") K8ResourceType type) {
        return ResponseEntity.ok(k8ResourceService.getNamespaceLevelK8Resources(clusterId, wsTenantName, type));
    }


    @GetMapping("v1/roles")
    public ResponseEntity<List<K8RoleResponse>> getK8RolesHandler(@RequestParam("tenantName") String wsTenantName,
                                                                  @RequestParam("cloud") CloudProviderType cloudProviderType,
                                                                  @RequestParam("k8ResourceLevel") K8ResourceLevel k8ResourceLevel) {
        return ResponseEntity.ok(k8ResourceService.getK8Roles(wsTenantName.trim(), cloudProviderType, k8ResourceLevel));
    }

    @GetMapping("v1/rolePolicies/byRoleUid")
    public ResponseEntity<List<K8RolePolicyRuleDTO>> getK8RolePoliciesByRoleUID(@RequestParam("roleUid") String roleUID,
                                                                                @RequestParam("tenantName") String wsTenantName,
                                                                                @RequestParam("cloud") CloudProviderType cloudProviderType) {
        return ResponseEntity.ok(k8ResourceService.getK8RolePoliciesByRoleUID(roleUID.trim(), wsTenantName, cloudProviderType));
    }


    @PatchMapping("/v1/publish")
    public ResponseEntity<Void> publishResourceByResourceIdAndTypeHandler(@Valid @RequestBody PublishResourceRequest request) {
        if (ObjectUtils.isEmpty(request.getCloudProviderType())) {
            throw new K8ResourceException("Cloud type is required. Eg: AZURE, AWS, GCP");
        }
        if (ObjectUtils.isEmpty(request.getClusterId())) {
            throw new K8ResourceException("Cluster ID is required");
        }
        publishResourceService.publishKubernetesResource(request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/v1/publish")
    public ResponseEntity<List<?>> getPublishedResourcesHandler(@RequestParam("type") PublishResourceType type,
                                                                @RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(publishResourceService.getPublishedKubernetesResources(wsTenantName, type));
    }
}
