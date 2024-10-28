package com.ws.azureAdIntegration.controller;

import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.service.AzureAdService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/azure/auth")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureAuthController {
    final AzureUserCredentialRepository azureUserCredentialRepository;
    //    final TenantAdminService tenantAdminService;
    final AzureAdService azureAdService;

    @Autowired
    public AzureAuthController(AzureUserCredentialRepository azureUserCredentialRepository, AzureAdService azureAdService) {
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.azureAdService = azureAdService;
    }

    @PostMapping("/configure")
    public ResponseEntity creatAzureConfiguration(@RequestBody Map<String, String> payload) {
        String clientId = payload.get("client_id");
        String tenantId = payload.get("tenant_id");
        String clientSecret = payload.get("client_secret");
        String objectId = payload.get("object_id");
//        String wsTenantEmail = payload.get("ws_tenant_email");
//        Long wsTenantId = tenantAdminRepository.findByEmail(wsTenantEmail)
//                .filter(tenantAdmin -> tenantAdmin.equals(Role.SYS_ADMIN))
//                .map(TenantAdmin::getId)
//                .orElseThrow(() -> new RuntimeException(String.format("Only %s can configure Azure AD", Role.SYS_ADMIN)));

        Integer wsTenantId = 1;

        if (Optional.ofNullable(getAzureUserCredentialForWSTenant(wsTenantId)).isPresent()) {
            throw new RuntimeException("Azure credentials already saved!");
        }

        AzureUserCredential azureUserCredential = AzureUserCredential.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .objectId(objectId)
                .wsTenantId(wsTenantId)
                .createdAt(new Date())
                .build();
        azureUserCredentialRepository.save(azureUserCredential);
        azureAdService.syncAzureData(wsTenantId, tenantId, clientId, clientSecret);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Collections.singletonMap("message", "User configured successfully!"));
    }


    @GetMapping("/configuration")
    public ResponseEntity fetchAzureConfiguration(@RequestParam("email") String email) {
        Integer wsTenantId = 1;

        AzureUserCredential azureUserCredential = Optional.ofNullable(getAzureUserCredentialForWSTenant(wsTenantId))
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found!"));
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureUserCredential);

    }

    private AzureUserCredential getAzureUserCredentialForWSTenant(Integer wsTenantId) {
        return azureUserCredentialRepository.findByWsTenantId(wsTenantId).orElse(null);
    }

}


