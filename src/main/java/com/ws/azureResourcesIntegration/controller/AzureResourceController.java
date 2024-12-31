package com.ws.azureResourcesIntegration.controller;

import com.ws.azureResourcesIntegration.constant.AzureResourcesType;
import com.ws.azureResourcesIntegration.dto.AzureRoleDefinitionDTO;
import com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse;
import com.ws.azureResourcesIntegration.entities.AzureServer;
import com.ws.azureResourcesIntegration.entities.AzureStorageAccount;
import com.ws.azureResourcesIntegration.entities.AzureVM;
import com.ws.azureResourcesIntegration.service.AzureResourceService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/azureResources")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceController {
    final AzureResourceService azureResourceService;

    @Autowired
    public AzureResourceController(AzureResourceService azureResourceService) {
        this.azureResourceService = azureResourceService;
    }

    @GetMapping("/v1/getAllVirtualMachines")
    public ResponseEntity<List<AzureVM>> getAllVirtualMachinesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getAllVirtualMachines(wsTenantName));
    }

    @GetMapping("/v1/getStorages")
    public ResponseEntity<List<AzureStorageAccount>> getStoragesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getStorages(wsTenantName));
    }

    @GetMapping("/v1/getServersWithDatabases")
    public ResponseEntity<List<AzureServer>> getServersWithDatavsesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getServersWithDatavses(wsTenantName));
    }

    @GetMapping("/v1/getRoleDefinitionsName")
    public ResponseEntity<List<Map<String, Object>>> getRoleDefinitionsNameWithIdHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getRoleDefinitionsNameWithId(wsTenantName));
    }

    @GetMapping("/v1/getAzureRoleDefinitionById")
    public ResponseEntity<AzureRoleDefinitionDTO> getAzureRoleDefinitionByIdHandler(@RequestParam("id") Integer azureRoleId, @RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getAzureRoleDefinitionDetailsUsingId(azureRoleId, wsTenantName));
    }

    @GetMapping("/v1/roleAssociations/{roleId}/wsTenants/{tenantName}/principleTypes/{type}")
    public ResponseEntity<List<AzureRolePrincipleAssociationResponse>> getAllUsersAssociatedWithRoleIdHandler(
            @PathVariable("roleId") String azureRoleId,
            @PathVariable("tenantName") String wsTenantName,
            @PathVariable("type") String principleType) {
        return ResponseEntity.ok(azureResourceService.getAllUsersAssociatedWithRoleId(azureRoleId, wsTenantName, principleType));
    }

    @GetMapping("/v1/scopes/{scopeType}/wsTenants/{tenantName}/principleTypes/{principleType}/assignees/{assignee}")
    public ResponseEntity<List<?>> getAzureVMsForPrincipleHandler(
            @PathVariable("scopeType") String scopeType,
            @PathVariable("principleType") String principleType,
            @PathVariable("tenantName") String wsTenantName,
            @PathVariable("assignee") String assignee) {
        return ResponseEntity.ok(azureResourceService.getAzureVMsForPrinciple(scopeType, principleType, assignee, wsTenantName));
    }

    @PatchMapping("/v1/publish")
    public ResponseEntity<Void> publishResourceHandler(@RequestParam("type") AzureResourcesType type, @RequestParam("id") Integer resourceId) {
        azureResourceService.publishResourceByResourceIdAndType(resourceId, type);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/v1/publish")
    public ResponseEntity<List<?>> getPublishedResourcesHandler(@RequestParam("type") AzureResourcesType type, @RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getPublishedResources(wsTenantName, type));
    }


}
