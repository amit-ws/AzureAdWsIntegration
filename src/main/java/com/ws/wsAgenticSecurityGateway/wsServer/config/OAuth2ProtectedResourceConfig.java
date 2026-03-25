package com.ws.wsAgenticSecurityGateway.wsServer.config;

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

/**
 * OAuth2 Protected Resource Metadata endpoint (RFC 9728).
 *
 * <p>When {@code ws.gateway.auth.mode=oauth2}, this endpoint tells MCP clients
 * (like Claude Desktop connectors) how to discover the Authorization Server.
 * The client flow is:
 * <ol>
 *   <li>Client connects to {@code /mcp} → gets 401 Unauthorized</li>
 *   <li>Client fetches {@code /.well-known/oauth-protected-resource}</li>
 *   <li>Client reads {@code authorization_servers[0]} → e.g., Keycloak issuer URI</li>
 *   <li>Client fetches {@code <issuer>/.well-known/openid-configuration} for endpoints</li>
 *   <li>Client performs Authorization Code + PKCE flow → gets JWT</li>
 *   <li>Client sends JWT as {@code Authorization: Bearer <token>} on all MCP requests</li>
 * </ol>
 *
 * <p>IdP-agnostic: just configure {@code OAUTH2_ISSUER_URI} to point at any
 * OIDC-compliant provider (Keycloak, Azure AD, Okta, Auth0).
 */
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

    /** Resolve issuer URI dynamically: DB config > env vars > null. */
    private String resolveIssuerUri() {
        return authConfigService.getEffectiveIssuerUri();
    }

    /**
     * RFC 9728 — OAuth 2.0 Protected Resource Metadata.
     * Returns the authorization server(s) that protect this resource.
     */
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

    /**
     * OAuth2 Authorization Server Metadata (RFC 8414).
     *
     * <p>MCP clients (like Claude.ai connectors) fetch this from the gateway's origin
     * to discover authorization/token endpoints. We proxy the IdP's OIDC discovery
     * document so the gateway acts as the OAuth2 metadata source.
     */
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

    /**
     * OAuth2 authorize redirect — forwards the client to the IdP's authorization endpoint.
     *
     * <p>MCP clients send the authorize request to the gateway's origin. We redirect
     * to the actual IdP authorization endpoint, preserving all query parameters
     * (response_type, client_id, redirect_uri, code_challenge, state, scope, etc.).
     */
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(jakarta.servlet.http.HttpServletRequest request) {
        String issuer = resolveIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            log.error("OAuth2 issuer URI not configured — cannot redirect to authorize");
            return ResponseEntity.internalServerError().build();
        }

        // Rewrite scope: MCP clients (like Claude.ai) may send custom scopes
        // (e.g., "claudeai") that the IdP doesn't recognize → invalid_scope error.
        // We replace with standard OIDC scopes that any IdP supports.
        String queryString = request.getQueryString();
        if (queryString != null) {
            queryString = queryString.replaceAll("scope=[^&]*", "scope=openid+email+profile");
        }

        // Use discovered authorization endpoint from DB config, or default Keycloak convention
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

    /**
     * OAuth2 token endpoint proxy — forwards token exchange requests to the IdP.
     *
     * <p>After the authorization code redirect, the MCP client sends the code exchange
     * to the gateway's /token endpoint. We proxy it to the IdP's token endpoint.
     */
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
            // Use discovered token endpoint from DB config, or default Keycloak convention
            String tokenEndpoint = null;
            var dbConfig = authConfigService.getActiveDbConfig();
            if (dbConfig.isPresent() && dbConfig.get().getTokenEndpoint() != null) {
                tokenEndpoint = dbConfig.get().getTokenEndpoint();
            } else {
                tokenEndpoint = issuer + "/protocol/openid-connect/token";
            }
            log.info("Proxying OAuth2 token exchange to IdP: {}", tokenEndpoint);

            // Read form parameters and forward them
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
