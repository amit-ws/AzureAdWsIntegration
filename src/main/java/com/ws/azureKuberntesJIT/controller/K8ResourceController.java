package com.ws.azureKuberntesJIT.controller;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.constants.PublishResourceType;
import com.ws.azureAdIntegration.exception.K8ResourceException;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.constant.K8ResourceType;
import com.ws.azureKuberntesJIT.dto.K8ResourceRequest;
import com.ws.azureKuberntesJIT.dto.K8RolePolicyRuleDTO;
import com.ws.azureKuberntesJIT.models.RaiseRequest;
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
    public ResponseEntity<List<?>> getK8ResourcesHandler(@RequestParam("level") K8ResourceLevel resourceLevel,
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
                                                                  @RequestParam("level") K8ResourceLevel k8ResourceLevel) {
        return ResponseEntity.ok(k8ResourceService.getK8Roles(wsTenantName.trim(), cloudProviderType, k8ResourceLevel));
    }

    @GetMapping("v1/roles/byClusterId")
    public ResponseEntity<List<K8RoleResponse>> getK8RolesHandler(@RequestParam("clusterId") String clusterId,
                                                                  @RequestParam("tenantName") String wsTenantName,
                                                                  @RequestParam("cloud") CloudProviderType cloudProviderType,
                                                                  @RequestParam("level") K8ResourceLevel k8ResourceLevel) {
        return ResponseEntity.ok(k8ResourceService.getK8Roles(wsTenantName.trim(), clusterId.trim(), cloudProviderType, k8ResourceLevel));
    }

    @GetMapping("v1/rolePolicies/byRoleUid")
    public ResponseEntity<List<K8RolePolicyRuleDTO>> getK8RolePoliciesByRoleUID(@RequestParam("uid") String roleUID,
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


    @GetMapping("/v1/applicableRoles")
    public ResponseEntity<List<?>> getPublishedResourcesHandler(@RequestParam("tenantName") String wsTenantName,
                                                                @RequestParam("type") String resourceType,
                                                                @RequestParam("cloudId") String resourceId,
                                                                @RequestParam("clusterId") String clusterId,
                                                                @RequestParam("cloudType") CloudProviderType cloudProviderType) {
        return ResponseEntity.ok(k8ResourceService.findApplicableRoles(wsTenantName, resourceType, resourceId, clusterId, cloudProviderType));
    }

    @PostMapping("/v1/raise/requests")
    public ResponseEntity<Boolean> getPublishedResourcesHandler(@RequestBody List<RaiseRequest> requests) {
        return ResponseEntity.ok(k8ResourceService.raiseRequest(requests));
    }


    @GetMapping("/v1/create/clusterRoleBinding")
    public ResponseEntity assignClusterRoleHandler(@RequestParam String clusterRoleName) {
        k8ResourceService.assignClusterRole(clusterRoleName);
        return ResponseEntity.ok().build();
    }


    @GetMapping("/v1/create/namespaceRoleBinding")
    public ResponseEntity assignNamespaceRoleHandler(@RequestParam String namespace, @RequestParam String namespaceRoleName) {
        k8ResourceService.assignNamespaceRole(namespace.trim(), namespaceRoleName.trim());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/v1/delete/clusterRoleBinding")
    public ResponseEntity deleteClusterRoleBindingusingNameHandler(@RequestParam String clusterRoleName) {
        k8ResourceService.deleteClusterRoleBindingUsingName(clusterRoleName.trim());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/v1/delete/namespaceRoleBinding")
    public ResponseEntity deleteNamespaceRoleBindingUsingName(@RequestParam String namespace, @RequestParam String namespaceRoleName) {
        k8ResourceService.deleteNamespaceRoleBindingUsingName(namespace.trim(), namespaceRoleName.trim());
        return ResponseEntity.noContent().build();
    }


    @PostMapping("/v1/create/namespaceRole")
    public ResponseEntity<String> createNamespaceRoleHandling(@RequestParam String namespace, @RequestBody List<String> resourceNames) {
        return ResponseEntity.ok(k8ResourceService.createNamespaceRole(namespace, resourceNames));
    }


    @GetMapping("/v1/test")
    public ResponseEntity testHandler() {
        k8ResourceService.test();
        return ResponseEntity.ok().build();
    }
}
