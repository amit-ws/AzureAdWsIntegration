package com.ws.controller;


import com.ws.service.TokenManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

    @Value("${spring.cloud.azure.active-directory.authBaseUrl}")
    private String baseUrl;

    @Value("${spring.cloud.azure.active-directory.tenant-id}")
    private String tenantId;

    @Value("${spring.cloud.azure.active-directory.client-id}")
    private String clientId;

    @Value("${spring.cloud.azure.active-directory.client-secret}")
    private String clientSecret;

    private final RestTemplate restTemplate;

    @Autowired
    public AuthenticationController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostMapping("/get-token")
    public ResponseEntity<Map<String, Object>> getToken() {
        final String tokenUrl = baseUrl + tenantId + "/oauth2/v2.0/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String body = String.format("client_id=%s&scope=https://graph.microsoft.com/.default&client_secret=%s&grant_type=client_credentials",
                clientId, clientSecret);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(URI.create(tokenUrl), HttpMethod.POST, entity, Map.class);
        TokenManager.getInstance().setAccessToken((String) response.getBody().get("access_token"));
        return ResponseEntity.ok(response.getBody());
    }

}
