package com.ws.azureResourcesIntegration.controller;

import com.ws.azureResourcesIntegration.constant.AzureResourcesType;
import com.ws.azureResourcesIntegration.constant.CustomRoleAssignmentStatus;
import com.ws.azureResourcesIntegration.dto.AssignRoleRequest;
import com.ws.azureResourcesIntegration.dto.AzureRoleDefinitionDTO;
import com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse;
import com.ws.azureResourcesIntegration.dto.ApplicableRoleDefinition;
import com.ws.azureResourcesIntegration.entities.*;
import com.ws.azureResourcesIntegration.service.AzureResourceService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
            @PathVariable("scopeType") AzureResourcesType scopeType,
            @PathVariable("principleType") String principleType,
            @PathVariable("tenantName") String wsTenantName,
            @PathVariable("assignee") String assignee) {
        return ResponseEntity.ok(azureResourceService.getAzureAzureResourcesForPrinciple(scopeType, principleType, assignee, wsTenantName));
    }

    @PatchMapping("/v1/publish")
    public ResponseEntity<Void> publishResourceHandler(@RequestParam("type") AzureResourcesType type,
                                                       @RequestParam("id") Integer resourceId) {
        azureResourceService.publishResourceByResourceIdAndType(resourceId, type);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/v1/publish")
    public ResponseEntity<List<?>> getPublishedResourcesHandler(@RequestParam("type") AzureResourcesType type,
                                                                @RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getPublishedResources(wsTenantName, type));
    }

    @GetMapping("/v1/applicableRoles")
    public ResponseEntity<List<ApplicableRoleDefinition>> getAllApplicableRoleDefinitionsForResourceHandler(@RequestParam String resourceType,
                                                                                                            @RequestParam Integer resourceId,
                                                                                                            @RequestParam AzureResourcesType type) {
        return ResponseEntity.ok(azureResourceService.getAllApplicableRoleDefinitionsForResource(resourceType, resourceId, type));
    }

    @PostMapping("/v1/requests/raise")
    public ResponseEntity<Boolean> raiseResourceAssignmentRequestHandler(@Valid @RequestBody AssignRoleRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body((azureResourceService.raiseResourceAssignmentRequest(request)));
    }

    @GetMapping("/v1/requests")
    public ResponseEntity<List<CustomRoleAssignment>> getAllRaiseRoleAssignmentRequestHandler(@RequestParam("tenantName") String wsTenantName, @RequestParam("state") CustomRoleAssignmentStatus status) {
        return ResponseEntity.ok(azureResourceService.getAllRaiseRoleAssignmentRequest(wsTenantName, status));
    }

    @PatchMapping("/v1/requests/manage")
    public ResponseEntity<Boolean> manageResourceRequestHandler(@RequestParam("id") Integer customRoleAssignmentId, @RequestParam("state") CustomRoleAssignmentStatus status) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body((azureResourceService.manageResourceRequest(customRoleAssignmentId, status)));
    }


    @PostMapping("/v1/assignRole")
    public ResponseEntity<AzureRoleAssignment> assignRoleToResourceForUserhandler(@Valid @RequestBody AssignRoleRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body((azureResourceService.assignRoleToPrincipalForResourceInAzure(request)));
    }

    @DeleteMapping("/v1/removeRole")
    public ResponseEntity<Boolean> revokeRoleAssignmentHandler(String roleAssignmentId) {
        return ResponseEntity.ok(azureResourceService.revokeRoleAssignment(roleAssignmentId));
    }


}
