package com.ws.azureAdIntegration.controller;

import com.ws.azureAdIntegration.dto.CreateAzureConfiguration;
import com.ws.azureAdIntegration.dto.AzureDataResponse;
import com.ws.azureAdIntegration.service.AzureADJwtValidationService;
import com.ws.azureAdIntegration.service.AzureADJwtValidator;
import com.ws.azureAdIntegration.service.AzureAuthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/azure/auth")
@Slf4j
public class AzureAuthController {
    private final AzureAuthService azureAuthService;
    final AzureADJwtValidator azureADJwtValidator;
    final AzureADJwtValidationService azureADJwtValidationService;

    @Autowired
    public AzureAuthController(AzureAuthService azureAuthService, AzureADJwtValidator azureADJwtValidator, AzureADJwtValidationService azureADJwtValidationService) {
        this.azureAuthService = azureAuthService;
        this.azureADJwtValidator = azureADJwtValidator;
        this.azureADJwtValidationService = azureADJwtValidationService;
    }

//    @PostMapping("/configure")
//    public ResponseEntity creatAzureConfiguration(@RequestBody @Valid CreateAzureConfiguration createAzureConfiguration) {
//        return ResponseEntity
//                .status(HttpStatus.CREATED)
//                .body(azureAuthService.creatAzureConfiguration(createAzureConfiguration));
//    }

    @PostMapping("/configure")
    public ResponseEntity creatAzureConfigurationV2Hanndler(@RequestBody @Valid CreateAzureConfiguration createAzureConfiguration) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(azureAuthService.creatAzureConfigurationV2(createAzureConfiguration));
    }

    @GetMapping("/configuration")
    public ResponseEntity fetchAzureConfiguration(@RequestParam String tenantName) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureAuthService.fetchAzureConfiguration(tenantName));
    }

    @GetMapping("/sso-login")
    public ResponseEntity ssoLoginUrlhandler(@RequestParam String email) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureAuthService.generateAzureSSOUrl(email));
    }

    @GetMapping("/keys")
    public ResponseEntity createAzureADPublicKeyMapHandler(@RequestParam String tenantId, @RequestParam String clientId) {
        azureADJwtValidationService.createAzureADPublicKeyMap(tenantId, clientId);
        return new ResponseEntity(HttpStatus.CREATED);
    }


    @PostMapping("/token-validate")
    public ResponseEntity isAzureADTokenValidHandler(@RequestBody Map<String, String> payload) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureADJwtValidationService.isAzureADTokenValid(payload.get("token")));
    }

//    @PatchMapping("/update/subscriptionId")
//    public ResponseEntity<AzureDataResponse> updateSubscriptionIdHandler(@RequestParam Integer credId, @RequestParam String subscriptionId) {
//        return ResponseEntity
//                .status(HttpStatus.OK)
//                .body(azureAuthService.updateSubscriptionId(credId, subscriptionId));
//    }

    @PatchMapping("v2/update/subscriptionId")
    public ResponseEntity<AzureDataResponse> updateSubscriptionIdsHandler(@RequestParam Integer credId, @RequestParam String wsTenantName,
                                                                          @RequestBody(required = false) Set<String> requestedSubIds) {
        if (CollectionUtils.isEmpty(requestedSubIds)) {
            requestedSubIds = new HashSet<>();
        }
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(azureAuthService.updateSubscriptionIds(credId, wsTenantName, requestedSubIds));
    }


    //    @GetMapping("/jwtValidate")
//    public ResponseEntity validateTokenHandler(@RequestParam Integer wsTenantId, @RequestParam String token) {
//        azureADJwtValidator.validate(wsTenantId, token);
//        return new ResponseEntity(HttpStatus.OK);
//    }

}




