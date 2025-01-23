package com.ws.azureResourcesIntegration.controller;

import com.ws.azureResourcesIntegration.constant.AzureResourcesType;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.dto.*;
import com.ws.azureResourcesIntegration.entities.*;
import com.ws.azureResourcesIntegration.service.AzureResourceService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
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
    public ResponseEntity<List<?>> getAllVirtualMachinesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getAzureResourcesUsingType(wsTenantName, AzureResourcesType.VIRTUAL_MACHINE));
    }

    @GetMapping("/v1/getStorages")
    public ResponseEntity<List<?>> getStoragesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getAzureResourcesUsingType(wsTenantName, AzureResourcesType.STORAGE_ACCOUNT));
    }

    @GetMapping("/v1/getServersWithDatabases")
    public ResponseEntity<List<?>> getServersWithDatabasesHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getAzureResourcesUsingType(wsTenantName, AzureResourcesType.SERVER));
    }

    @GetMapping("/v1/getSubscriptions")
    public ResponseEntity<List<?>> getSubscriptionsHandler(@RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity.ok(azureResourceService.getAzureResourcesUsingType(wsTenantName, AzureResourcesType.SUBSCRIPTION));
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
    public ResponseEntity<List<?>> getAzureAzureResourcesForPrincipleHandler(
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
    public ResponseEntity<List<ApplicableRoleDefinition>> getAllApplicableRoleDefinitionsForResourceHandler(@RequestParam Integer resourceId,
                                                                                                            @RequestParam AzureResourcesType type) {
        return ResponseEntity.ok(azureResourceService.getAllApplicableRoleDefinitionsForResource(resourceId, type));
    }

    @PostMapping("/v1/requests/raise")
    public ResponseEntity<CustomRoleAssignment> raiseResourceAssignmentRequestHandler(@Valid @RequestBody AssignRoleRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body((azureResourceService.raiseResourceAssignmentRequest(request)));
    }

    @GetMapping("/v1/requests/all")
    public ResponseEntity<Collection<CustomRoleAssignmentDTO>> getAllRaiseRoleAssignmentRequestHandler(@RequestParam("tenantName") String wsTenantName,
                                                                                 @RequestParam(value = "state", required = false) RequestStatus status,
                                                                                 @RequestParam(value = "email", required = false) String userEmail) {
        return ResponseEntity.ok(azureResourceService.getAllRaisedRoleAssignmentRequest(wsTenantName, status, userEmail));
    }

//    @GetMapping("/v1/requests/allData")
//    public ResponseEntity<Collection<?>> getAllRaisedRoleAssignmentRequestALLHandler(@RequestParam("tenantName") String wsTenantName, @RequestParam(value = "state", required = false) RequestStatus status) {
//        return ResponseEntity.ok(azureResourceService.getAllRaisedRoleAssignmentRequestALL(wsTenantName, status));
//    }

    @PatchMapping("/v1/requests/process")
    public ResponseEntity<Boolean> processResourceRequestForPrincipleHandler(@RequestParam("id") Integer customRoleAssignmentId, @RequestParam("status") RequestStatus updatedStatus) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body((azureResourceService.processResourceRequestForPrinciple(customRoleAssignmentId, updatedStatus)));
    }

    @PostMapping("/v1/assignRole")
    public ResponseEntity<AzureRoleAssignment> assignRoleToResourceForUserHandler(@Valid @RequestBody AssignRoleRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body((azureResourceService.assignRoleToPrincipalForResourceInAzure(request)));
    }

    @DeleteMapping("/v1/removeRole")
    public ResponseEntity<Boolean> revokeRoleAssignmentHandler(@RequestParam String azureId) {
        return ResponseEntity.ok(azureResourceService.revokeRoleAssignment(azureId));
    }

}
