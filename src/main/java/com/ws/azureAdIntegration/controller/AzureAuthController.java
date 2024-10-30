package com.ws.azureAdIntegration.controller;


import com.ws.azureAdIntegration.dto.CreateAzureConfiguration;
import com.ws.azureAdIntegration.service.AzureAuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/azure/auth")
public class AzureAuthController {
    private final AzureAuthService azureAuthService;

    @Autowired
    public AzureAuthController(AzureAuthService azureAuthService) {
        this.azureAuthService = azureAuthService;
    }

    @PostMapping("/configure")
    public ResponseEntity creatAzureConfiguration(@RequestBody @Valid CreateAzureConfiguration createAzureConfiguration) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(azureAuthService.creatAzureConfiguration(createAzureConfiguration));
    }

    @GetMapping("/configuration")
    public ResponseEntity fetchAzureConfiguration(@RequestParam("email") String email) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(azureAuthService.fetchAzureConfiguration(email));
    }
}




