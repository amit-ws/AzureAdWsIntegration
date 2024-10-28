package com.ws.controller;

import com.ws.service.TokenManager;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureAdWsIntegrationController {
    @Value("${spring.cloud.azure.active-directory.client-id}")
    String clientId;

    @Value("${spring.cloud.azure.active-directory.client-secret}")
    String clientSecret;

    @Value("${spring.cloud.azure.active-directory.tenant-id}")
    String tenantId;

    @Value("${spring.cloud.azure.active-directory.redirect-uri}")
    String redirectUri;

    @Value("${spring.cloud.azure.active-directory.authBaseUrl}")
    String authBaseUrl;

    @Value("${spring.cloud.azure.active-directory.token-uri}")
    String tokeUri;
    final UUID stateCode = UUID.randomUUID();
    final RestTemplate restTemplate;


    @Autowired
    public AzureAdWsIntegrationController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/login")
    public String login() throws UnsupportedEncodingException {
        return "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/authorize"
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
                + "&response_mode=query"
                + "&scope=" + URLEncoder.encode("offline_access User.Read Mail.Read", "UTF-8")
                + "&state=" + stateCode;
    }

    @GetMapping("/callback")
    public ResponseEntity callback(@RequestParam("code") String code, @RequestParam("state") String state) {
        if (!state.equals(stateCode.toString())) {
            log.info(String.format("State didn't match with what client return. Original: %s Response: %s", stateCode, state));
            throw new RuntimeException("Invalid request");
        }

        Map tokenResponse = exchangeCodeForAccessToken(code);
        TokenManager tokenManager = TokenManager.getInstance();
        tokenManager.setFieldValues(tokenResponse);
        return ResponseEntity.ok(tokenResponse);
    }

    @GetMapping("/getToken")
    public ResponseEntity getTokenHandler() {
        return ResponseEntity.ok(TokenManager.getInstance());
    }

    @GetMapping("/refreshToken")
    public ResponseEntity refreshTokenHandler() {
        refreshAccessToken();
        return ResponseEntity.ok(TokenManager.getInstance());
    }


    private Map exchangeCodeForAccessToken(String code) {
        String url = authBaseUrl + tenantId + tokeUri;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("scope", "User.Read Mail.Read");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);
        body.add("grant_type", "authorization_code");
        body.add("client_secret", clientSecret);

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, Map.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            return response.getBody();
        } else {
            log.error("Error fetching access token: {}", response.getStatusCode());
            throw new RuntimeException("Failed to fetch access token");
        }
    }

    private Map refreshAccessToken() {
        String url = authBaseUrl + tenantId + tokeUri;
        TokenManager tokenManager = TokenManager.getInstance();
        if (ObjectUtils.isEmpty(tokenManager) && StringUtils.isEmpty(tokenManager.getRefreshToken())) {
            throw new RuntimeException("No refresh token found. Please login!");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("scope", "User.Read Mail.Read");
        body.add("refresh_token", tokenManager.getRefreshToken());
        body.add("grant_type", "refresh_token");
        body.add("client_secret", clientSecret);

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, Map.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            return response.getBody();
        } else {
            log.error("Error refreshing access token: {}", response.getStatusCode());
            throw new RuntimeException("Failed to refresh access token");
        }
    }

//    @GetMapping("/fetchMyProfile")
//    public Map<String, Object> fetchMyProfile() {
//        final String accessToken = TokenManager.getInstance().getAccessToken();
//        String url = "https://graph.microsoft.com/v1.0/me";
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.set("Authorization", "Bearer " + accessToken);
//        HttpEntity<String> entity = new HttpEntity<>(headers);
//
//        RestTemplate restTemplate = new RestTemplate();
//        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
//
//        if (response.getStatusCode().is2xxSuccessful()) {
//            String usersJson = response.getBody();
//            log.info("Users: {}", usersJson);
//
//            ObjectMapper objectMapper = new ObjectMapper();
//            Map<String, Object> userProfile;
//            try {
//                userProfile = objectMapper.readValue(usersJson, Map.class);
//            } catch (JsonProcessingException e) {
//                throw new RuntimeException(e);
//            }
//            return userProfile;
//        } else {
//            log.error("Error fetching users: {}", response.getStatusCode());
//            throw new RuntimeException("Error status: " + response.getStatusCode());
//        }
//    }


}
