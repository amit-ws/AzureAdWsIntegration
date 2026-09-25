package com.ws.wsAgenticSecurityGateway.security;

import com.ws.wsAgenticSecurityGateway.authConfig.service.AuthConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class OAuth2ProtectedResourceConfig {

    private final AuthConfigService authConfigService;

    @Value("${server.port:9492}")
    private int serverPort;

    private final RestTemplate restTemplate = new RestTemplate();

    public OAuth2ProtectedResourceConfig(AuthConfigService authConfigService) {
        this.authConfigService = authConfigService;
    }

    private String resolveIssuerUri() {
        return authConfigService.getEffectiveIssuerUri();
    }

    @GetMapping(value = "/.well-known/oauth-protected-resource",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> protectedResourceMetadata() {
        String issuer = resolveIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            if (!"oauth2".equals(authConfigService.getEffectiveMode())) {
                return ResponseEntity.notFound().build();
            }
            log.error("OAuth2 issuer URI not configured — cannot serve protected resource metadata");
            return ResponseEntity.internalServerError().build();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", "http://localhost:" + serverPort);
        metadata.put("authorization_servers", List.of(issuer));
        metadata.put("scopes_supported", List.of("openid", "email", "profile"));
        metadata.put("bearer_methods_supported", List.of("header"));

        log.debug("Serving OAuth2 protected resource metadata → AS: {}", issuer);
        return ResponseEntity.ok(metadata);
    }

    @GetMapping(value = "/.well-known/oauth-authorization-server",
                produces = MediaType.APPLICATION_JSON_VALUE)
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> authorizationServerMetadata() {
        String issuer = resolveIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            if (!"oauth2".equals(authConfigService.getEffectiveMode())) {
                return ResponseEntity.notFound().build();
            }
            log.error("OAuth2 issuer URI not configured");
            return ResponseEntity.internalServerError().build();
        }

        try {
            String oidcUrl = issuer + "/.well-known/openid-configuration";
            log.debug("Fetching IdP OIDC metadata from: {}", oidcUrl);
            Map<String, Object> idpMetadata = restTemplate.getForObject(oidcUrl, Map.class);

            if (idpMetadata == null) {
                log.error("IdP returned null OIDC metadata");
                return ResponseEntity.internalServerError().build();
            }

            log.info("Serving OAuth2 authorization server metadata (proxied from IdP)");
            return ResponseEntity.ok(idpMetadata);
        } catch (Exception e) {
            log.error("Failed to fetch IdP OIDC metadata: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(jakarta.servlet.http.HttpServletRequest request) {
        String issuer = resolveIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            log.error("OAuth2 issuer URI not configured — cannot redirect to authorize");
            return ResponseEntity.internalServerError().build();
        }

        String queryString = request.getQueryString();
        if (queryString != null) {
            queryString = queryString.replaceAll("scope=[^&]*", "scope=openid+email+profile");
        }

        String authEndpoint = null;
        var dbConfig = authConfigService.getActiveDbConfig();
        if (dbConfig.isPresent() && dbConfig.get().getAuthorizationEndpoint() != null) {
            authEndpoint = dbConfig.get().getAuthorizationEndpoint();
        } else {
            authEndpoint = issuer + "/protocol/openid-connect/auth";
        }
        String redirectUrl = queryString != null ? authEndpoint + "?" + queryString : authEndpoint;

        log.info("Redirecting OAuth2 authorize request to IdP: {}", authEndpoint);
        log.debug("Full redirect URL: {}", redirectUrl);
        return ResponseEntity.status(302)
                .header("Location", redirectUrl)
                .build();
    }

    @org.springframework.web.bind.annotation.PostMapping(value = "/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> token(jakarta.servlet.http.HttpServletRequest request) {
        String issuer = resolveIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            log.error("OAuth2 issuer URI not configured — cannot proxy token request");
            return ResponseEntity.internalServerError().build();
        }

        try {
            String tokenEndpoint = null;
            var dbConfig = authConfigService.getActiveDbConfig();
            if (dbConfig.isPresent() && dbConfig.get().getTokenEndpoint() != null) {
                tokenEndpoint = dbConfig.get().getTokenEndpoint();
            } else {
                tokenEndpoint = issuer + "/protocol/openid-connect/token";
            }
            log.info("Proxying OAuth2 token exchange to IdP: {}", tokenEndpoint);

            Map<String, String[]> params = request.getParameterMap();
            org.springframework.util.LinkedMultiValueMap<String, String> formData =
                    new org.springframework.util.LinkedMultiValueMap<>();
            params.forEach((key, values) -> {
                for (String value : values) {
                    formData.add(key, value);
                }
            });

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, String>> entity =
                    new org.springframework.http.HttpEntity<>(formData, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(tokenEndpoint, entity, String.class);
            return ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.getBody());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("IdP token exchange failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Token proxy error: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("{\"error\":\"token_proxy_error\"}");
        }
    }
}
