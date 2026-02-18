//package com.ws.controller;
//
//import jakarta.servlet.http.HttpSession;
//import lombok.AccessLevel;
//import lombok.experimental.FieldDefaults;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.*;
//import org.springframework.security.core.annotation.AuthenticationPrincipal;
//import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
//import org.springframework.security.oauth2.core.user.OAuth2User;
//import org.springframework.stereotype.Controller;
//import org.springframework.util.LinkedMultiValueMap;
//import org.springframework.util.MultiValueMap;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//import org.springframework.web.client.RestTemplate;
//
//import java.util.Map;
//
//@RestController
//@RequestMapping("/api/v1")
//@Slf4j
//@FieldDefaults(level = AccessLevel.PRIVATE)
//public class AzureController {
//    final String TENANT_ID = "00b1d06b-e316-45af-a6d2-2734f62a5acd";
//    final String CLIENT_ID = "9acacaf6-02e1-4e06-84d9-5da4a7ffd2aa";
//    final String CLIENT_SECRET = "sJB8Q~G-YDCgTRPv6J~LZCQkNyDyUATwQvP_Bcx0";
//    final String CALLBACK = "http://localhost:9495/api/callback";
//
//    @Autowired
//    private OAuth2AuthorizedClientService authorizedClientService;
//
//    final RestTemplate restTemplate;
//
//    public AzureController(RestTemplate restTemplate) {
//        this.restTemplate = restTemplate;
//    }
//
//    @GetMapping("/login")
//    public String generateAzureLoginUrl() {
//        String tenantId = TENANT_ID;
//        String clientId = CLIENT_ID;
//        String redirectUri = CALLBACK;
//        String userEmail = "amit@whiteswansecurity.com";
//
//        return "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/authorize?" +
//                "client_id=" + clientId +
//                "&response_type=code" +
//                "&redirect_uri=" + redirectUri +
//                "&scope=openid" +
//                "&login_hint=" + userEmail +
//                "&prompt=none";
//    }
//
//    @GetMapping("/callback")
//    public String handleCallback(@RequestParam("code") String code, HttpSession session) {
//        String accessToken = exchangeCodeForToken(code);
//        session.setAttribute("accessToken", accessToken);
//        return "redirect:https://portal.azure.com";
//    }
//
//    private String exchangeCodeForToken(String code) {
//        String tokenUrl = "https://login.microsoftonline.com/YOUR_TENANT_ID/oauth2/v2.0/token";
//
//        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
//        map.add("client_id", CLIENT_ID);
//        map.add("client_secret", CLIENT_SECRET);
//        map.add("code", code);
//        map.add("redirect_uri", "http://localhost:8080/callback");
//        map.add("grant_type", "authorization_code");
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
//        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);
//
//        ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, Map.class);
//        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
//            throw new RuntimeException("Failed to get access token");
//        }
//
//        return  (String) response.getBody().get("access_token");
//    }
//}
