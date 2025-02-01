package com.ws.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureAdIntegration.util.EncryptionUtil;
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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

    final AzureUserRepository azureUserRepository;
    final AzureUserCredentialRepository azureUserCredentialRepository;


    @Autowired
    public AzureAdWsIntegrationController(RestTemplate restTemplate, AzureUserRepository azureUserRepository, AzureUserCredentialRepository azureUserCredentialRepository) {
        this.restTemplate = restTemplate;
        this.azureUserRepository = azureUserRepository;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
    }

//    @GetMapping("/login")
//    public String login() throws UnsupportedEncodingException {
//        return "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/authorize"
//                + "?client_id=" + clientId
//                + "&response_type=code"
//                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
//                + "&response_mode=query"
//                + "&scope=" + URLEncoder.encode("offline_access User.Read Mail.Read Directory.Read.All", StandardCharsets.UTF_8)
//                + "&state=" + stateCode;
////        offline_access User.Read Mail.Read
////        offline_access User.Read Mail.Read Directory.Read.All
//
//    }

    @GetMapping("/login")
    public String login() throws UnsupportedEncodingException {
        return "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/authorize"
                + "?client_id=" + clientId
                + "&response_type=code id_token"
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&response_mode=form_post"
                + "&scope=" + URLEncoder.encode("openid profile email User.Read Mail.Read Directory.Read.All", StandardCharsets.UTF_8)
                + "&nonce=" + UUID.randomUUID();
    }


    @PostMapping("/callback")
    public ResponseEntity callback(
            @RequestParam("code") String code) throws Exception {
        Map<String, String> tokens = exchangeCodeForTokens(code);
        String loginUrl = generateAzureSignInUrl(tokens.get("id_token"));
        return ResponseEntity.ok(loginUrl);
    }


    private Map<String, String> exchangeCodeForTokens(String code)  {
        log.info("exchangeCodeForTokens");
        String url = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("scope", "openid profile email User.Read Mail.Read");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);
        body.add("grant_type", "authorization_code");
        body.add("client_secret", clientSecret);

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, Map.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            Map<String, String> tokenMap = new HashMap<>();
//            tokenMap.put("access_token", (String) response.getBody().get("access_token"));
            tokenMap.put("id_token", (String) response.getBody().get("id_token"));
            log.info("Tokens: {}", tokenMap);
            return tokenMap;
        } else {
            log.error("Error fetching tokens: {}", response.getStatusCode());
            throw new RuntimeException("Failed to fetch tokens");
        }
    }

    private static String generateAzureSignInUrl(String idToken) {
        try {
            String signInUrl = "https://portal.azure.com/?id_token="
                    + URLEncoder.encode(idToken, StandardCharsets.UTF_8)
                    + "&session_mode=always";
            return signInUrl;
        } catch (Exception e) {
            System.err.println("Error generating Azure Sign-In URL: " + e.getMessage());
        }
        return null;
    }


//    @GetMapping("/callback")
//    public ResponseEntity callback(@RequestParam("code") String code, @RequestParam("state") String state) throws Exception {
//        if (!state.equals(stateCode.toString())) {
//            log.info(String.format("State didn't match with what client return. Original: %s Response: %s", stateCode, state));
//            throw new RuntimeException("Invalid request");
//        }
//
////        Map tokenResponse = exchangeCodeForAccessToken(code);
////        TokenManager tokenManager = TokenManager.getInstance();
////        tokenManager.setFieldValues(tokenResponse);
////        return ResponseEntity.ok(tokenManager.getAccessToken());
//
//        String url = getAzureFederatedSSOUrl("amit@whiteswansecurity.com", code);
//        return ResponseEntity.ok(url);
//    }

//    @GetMapping("/callback")
//    public ResponseEntity callback(@RequestParam("code") String code) {
////        Map tokenResponse = exchangeCodeForAccessToken(code);
//
//        try {
////            Map tokenResponse = exchangeCodeForAccessToken(code);
////            log.info("token: {}", tokenResponse.get("access_token"));
//
//            exchangeCodeForToken("ramki@wsazuread.onmicrosoft.com", code);
//
//        } catch (Exception e) {
//            throw new RuntimeException(e.getMessage());
//        }
////
////        TokenManager tokenManager = TokenManager.getInstance();
////        tokenManager.setFieldValues(tokenResponse);
//        return ResponseEntity.ok().build();
//    }


    @GetMapping("/getToken")
    public ResponseEntity getTokenHandler() {
        return ResponseEntity.ok(TokenManager.getInstance());
    }

    @GetMapping("/refreshToken")
    public ResponseEntity refreshTokenHandler() {
        refreshAccessToken();
        return ResponseEntity.ok(TokenManager.getInstance());
    }


    private Map exchangeCodeForAccessToken(String code) throws Exception {
        log.info("Into exchangeCodeForAccessToken");
//        AzureUserCredential azureUserCredential = getAzureUserCredentialForWSTenant(getAzureUserUsingEmail("ramki@wsazuread.onmicrosoft.com").getWsTenantName());

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
            log.info("token: {}", response.getBody().get("access_token"));
            return response.getBody();
        } else {
            log.error("Error fetching access token: {}", response.getStatusCode());
            throw new RuntimeException("Failed to fetch access token");
        }
    }


    public String getAzureFederatedSSOUrl(String userPrincipalName, String code) throws Exception {
        log.info("Inside getAzureFederatedSSOUrl.........");
        String tokenUrl = "https://login.microsoftonline.com/" + tenantId + "/oauth2/token";

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("resource", "https://management.azure.com/");
        body.add("code", code);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Failed to get access token");
        }

        String accessToken = (String) response.getBody().get("access_token");
        return accessToken;

//        // Step 2: Exchange this token for a session token
//        String federatedTokenUrl = "https://portal.azure.com/" + tenantId + "/federation?user=" + userPrincipalName + "&token=" + accessToken;
//
//        return "https://portal.azure.com?login_hint=" + "amit@whiteswansecurity.com" + "&sessionIdToken=" + accessToken + "&exp=" + (System.currentTimeMillis() / 1000 + 3600);

//        return federatedTokenUrl;
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

    @GetMapping("/fetchMyProfile")
    public Map<String, Object> fetchMyProfile(@RequestParam String accessToken) {
//        final String accessToken = TokenManager.getInstance().getAccessToken();
        String url = "https://graph.microsoft.com/v1.0/me";

        log.info("into fetch /me");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            String usersJson = response.getBody();
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> userProfile;
            try {
                userProfile = objectMapper.readValue(usersJson, Map.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            return userProfile;
        } else {
            log.error("Error fetching users: {}", response.getStatusCode());
            throw new RuntimeException("Error status: " + response.getStatusCode());
        }
    }


    public void exchangeCodeForToken(String email, String code) throws Exception {
//        AzureUserCredential azureUserCredential = getAzureUserCredentialForWSTenant(getAzureUserUsingEmail(email).getWsTenantName());
        String tokenUrl = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token";
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("code", code);
        body.add("redirect_uri", "http://localhost:9495/api/callback");
        body.add("grant_type", "authorization_code");
        body.add("scope", "User.Read Mail.Read");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, Map.class);
            log.info("response: {}", response);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map responseBody = response.getBody();

                log.info("token: {}", responseBody.get("access_token"));
//                JsonNode jsonResponse = new ObjectMapper().readTree(responseBody);
//                String accessToken = jsonResponse.get("access_token").asText();
//                String refreshToken = jsonResponse.get("refresh_token").asText();

//                AzureTokenResponse azureTokenResponse = new AzureTokenResponse();
//                azureTokenResponse.setAccess_token(accessToken);
//                azureTokenResponse.setRefresh_token(refreshToken);
//                return azureTokenResponse;
            } else {
//                return null;
            }


        } catch (Exception e) {
            System.err.println("Error exchanging authorization code for token: " + e.getMessage());
        }
//        return null;
    }

    private AzureUserCredential getAzureUserCredentialForWSTenant(String wsTenantName) {
        return azureUserCredentialRepository.findByWsTenantName(wsTenantName).orElse(null);
    }

    private AzureUser getAzureUserUsingEmail(String email) {
        return azureUserRepository.findByUserPrincipalName(email)
                .map(azureUser -> {
                    if (azureUser.getIsSSOEnabled() == null || !azureUser.getIsSSOEnabled()) {
                        throw new RuntimeException("Azure user not SSO enabled");
                    }
                    return azureUser;
                })
                .orElseThrow(() -> new RuntimeException(String.format("No Azure User found with provided email: %s", email)));
    }

}