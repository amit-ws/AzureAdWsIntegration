package com.ws.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class WsAzureAdService {
    @Value("${spring.cloud.azure.active-directory.graphBaseUrl}")
    private String graphBaseUrl;
    private final RestTemplate restTemplate;

    @Autowired
    public WsAzureAdService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ResponseEntity getUsers() {
        final String baseUrl = graphBaseUrl + "/users";
        return createRequestAndGetResponse(returnIfAccessTokenExist(), baseUrl);
    }

    public ResponseEntity getGroups() {
        final String baseUrl = graphBaseUrl + "/groups";
        return createRequestAndGetResponse(returnIfAccessTokenExist(), baseUrl);
    }

    public ResponseEntity createGroup(Map<String, Object> groupDetails) {
        final String baseUrl = graphBaseUrl + "/groups";
        final String accesToken = returnIfAccessTokenExist();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", accesToken);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(groupDetails, headers);
        return restTemplate.exchange(baseUrl, HttpMethod.POST, entity, Map.class);
    }

    private String returnIfAccessTokenExist() {
        String accessToken = TokenManager.getInstance().getAccessToken();
        if (StringUtils.isEmpty(accessToken)) {
            throw new RuntimeException("No access token found!");
        }
        return accessToken;
    }

    private ResponseEntity createRequestAndGetResponse(String accessToken, String baseUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", accessToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(baseUrl, HttpMethod.GET, entity, Map.class);
    }

}
