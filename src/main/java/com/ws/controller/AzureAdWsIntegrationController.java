package com.ws.controller;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Slf4j
public class AzureAdWsIntegrationController {

    private final RestTemplate restTemplate;

    private final String clientId = "9acacaf6-02e1-4e06-84d9-5da4a7ffd2aa";
    private final String clientSecret = "sJB8Q~G-YDCgTRPv6J~LZCQkNyDyUATwQvP_Bcx0";
    private final String tenantId = "00b1d06b-e316-45af-a6d2-2734f62a5acd";
    private final String redirectUri = "http://localhost:9495/login/oauth2/code/";

    @Autowired
    public AzureAdWsIntegrationController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/login")
    public String login() {
        log.info("into login");
        String authorizationUrl = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/authorize"
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&redirect_uri=" + redirectUri
                + "&response_mode=query"
                + "&scope=openid https://graph.microsoft.com/User.Read https://graph.microsoft.com/Directory.Read.All"
                + "&state=12345";

        log.info("authorizationUrl: {}", authorizationUrl);
        return "redirect:" + authorizationUrl;
    }

    @GetMapping("/login/oauth2/code")
    public String callback(@RequestParam("code") String code, @RequestParam("state") String state, @RequestParam("session_state") String sessionState) {
        log.info("into /callback");
        log.info("code: {}", code);
        String accessToken = exchangeAuthorizationCodeForToken(code);
        fetchUsers(accessToken);
        return "Access token received. Check logs for user data.";
    }

    private String exchangeAuthorizationCodeForToken(String code) {
        log.info("Initiating access token exchange");
        String tokenUrl = "https://login.microsoftonline.com/00b1d06b-e316-45af-a6d2-2734f62a5acd/oauth2/v2.0/token";

        MultiValueMap<String, String> tokenParams = new LinkedMultiValueMap<>();
        tokenParams.add("grant_type", "authorization_code");
        tokenParams.add("client_id", clientId);
        tokenParams.add("client_secret", clientSecret);
        tokenParams.add("code", code);
        tokenParams.add("redirect_uri", redirectUri);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> tokenRequest = new HttpEntity<>(tokenParams, headers);
        ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(tokenUrl, tokenRequest, Map.class);

        if (tokenResponse.getStatusCode().is2xxSuccessful()) {
            return (String) tokenResponse.getBody().get("access_token");
        } else {
            log.error("Error fetching access token: {}", tokenResponse.getStatusCode());
            throw new RuntimeException("Failed to fetch access token");
        }
    }


    public void fetchUsers(String accessToken) {
        log.info("intp /fetchUsers");
        String url = "https://graph.microsoft.com/v1.0/users";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            String usersJson = response.getBody();
            log.info("Users: {}", usersJson);
        } else {
            log.error("Error fetching users: {}", response.getStatusCode());
        }
    }






}
