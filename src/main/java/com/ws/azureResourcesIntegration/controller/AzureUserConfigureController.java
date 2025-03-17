package com.ws.azureResourcesIntegration.controller;

import com.azure.core.annotation.Get;
import com.ws.azureResourcesIntegration.dto.AzureUserConfigureRequest;
import com.ws.azureResourcesIntegration.entities.AzureUserConfigure;
import com.ws.azureResourcesIntegration.service.AzureUserConfigureService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/azureUserConfigure")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureUserConfigureController {
    final AzureUserConfigureService azureUserConfigureService;

    @Autowired
    public AzureUserConfigureController(AzureUserConfigureService azureUserConfigureService) {
        this.azureUserConfigureService = azureUserConfigureService;
    }

    @GetMapping("v1/get")
    public ResponseEntity<AzureUserConfigure> findByUserEmailAndWsTenantNameHandler(@RequestParam("email") String userEmail, @RequestParam("tenantName") String wsTenantName) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureUserConfigureService.findByUserEmailAndWsTenantName(userEmail.trim(), wsTenantName.trim()));
    }

    @PostMapping("v1/create")
    public ResponseEntity<AzureUserConfigure> configureAzureUserHandler(@Valid @RequestBody AzureUserConfigureRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(azureUserConfigureService.configureAzureUser(request));
    }

    @PatchMapping("v1/update")
    public ResponseEntity<AzureUserConfigure> updateAzureUserUpnHandler(@RequestParam("id") Integer id, @RequestParam("upn") String azureUserUpn) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(azureUserConfigureService.updateAzureUserUpn(id, azureUserUpn.trim()));
    }


    @GetMapping("/get")
    public ResponseEntity dataGet(@RequestParam String id, @RequestParam String tenantName) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureUserConfigureService.findUser(id.trim(), tenantName.trim()));
    }

}
