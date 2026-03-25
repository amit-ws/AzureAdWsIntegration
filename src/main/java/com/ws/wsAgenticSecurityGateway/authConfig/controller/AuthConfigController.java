package com.ws.wsAgenticSecurityGateway.authConfig.controller;

import com.ws.wsAgenticSecurityGateway.authConfig.dto.*;
import com.ws.wsAgenticSecurityGateway.authConfig.service.AuthConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for managing the gateway's authentication configuration.
 *
 * <p>Single-tenant: one auth config row in the DB. Supports CRUD, OIDC auto-discovery,
 * IdP connectivity validation, and JWKS force-refresh.
 *
 * <p>All operations capture full forensic audit trail (adminIdentity + IP address).
 */
@RestController
@RequestMapping("/api/admin/auth-config")
@Slf4j
public class AuthConfigController {

    private final AuthConfigService authConfigService;

    public AuthConfigController(AuthConfigService authConfigService) {
        this.authConfigService = authConfigService;
    }

    /**
     * GET /api/admin/auth-config — Returns the effective auth config.
     * Resolution: DB config (if exists) → env vars → defaults (mode=none).
     */
    @GetMapping
    public ResponseEntity<AuthConfigResponse> getAuthConfig() {
        return ResponseEntity.ok(authConfigService.getEffectiveConfig());
    }

    /**
     * POST /api/admin/auth-config — Create the initial auth config.
     * Fails if a config already exists (use PUT to update).
     */
    @PostMapping
    public ResponseEntity<?> createAuthConfig(@Valid @RequestBody AuthConfigRequest request,
                                               HttpServletRequest httpRequest) {
        try {
            String adminIdentity = resolveAdminIdentity(httpRequest);
            String sourceIp = resolveClientIp(httpRequest);
            AuthConfigResponse response = authConfigService.createAuthConfig(request, adminIdentity, sourceIp);
            log.info("Auth config created by {} from {}", adminIdentity, sourceIp);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorMap(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create auth config: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorMap("Failed to create auth config: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/admin/auth-config — Update existing auth config.
     * Triggers runtime hot-swap of JwtDecoder if issuer/mode changes.
     */
    @PutMapping
    public ResponseEntity<?> updateAuthConfig(@Valid @RequestBody AuthConfigRequest request,
                                               HttpServletRequest httpRequest) {
        try {
            String adminIdentity = resolveAdminIdentity(httpRequest);
            String sourceIp = resolveClientIp(httpRequest);
            AuthConfigResponse response = authConfigService.updateAuthConfig(request, adminIdentity, sourceIp);
            log.info("Auth config updated by {} from {}", adminIdentity, sourceIp);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap(e.getMessage()));
        } catch (ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(errorMap("Configuration was modified by another admin. Please refresh and try again."));
        } catch (Exception e) {
            log.error("Failed to update auth config: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorMap("Failed to update auth config: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/admin/auth-config — Remove DB config, revert to env vars or defaults.
     */
    @DeleteMapping
    public ResponseEntity<?> deleteAuthConfig(HttpServletRequest httpRequest) {
        try {
            String adminIdentity = resolveAdminIdentity(httpRequest);
            String sourceIp = resolveClientIp(httpRequest);
            authConfigService.deleteAuthConfig(adminIdentity, sourceIp);
            log.info("Auth config deleted by {} from {} — reverting to env vars or defaults", adminIdentity, sourceIp);
            return ResponseEntity.ok(Map.of("status", "deleted", "message", "Auth config removed. Reverted to environment variables or defaults."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete auth config: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorMap("Failed to delete auth config: " + e.getMessage()));
        }
    }

    /**
     * POST /api/admin/auth-config/validate — Test IdP connectivity.
     * Checks that JWKS endpoint is reachable and returns valid keys.
     */
    @PostMapping("/validate")
    public ResponseEntity<AuthConfigValidationResponse> validateConfig(
            @RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        String issuerUri = body.get("issuerUri");
        String jwksUri = body.get("jwksUri");
        if (issuerUri == null || issuerUri.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String adminIdentity = resolveAdminIdentity(httpRequest);
        String sourceIp = resolveClientIp(httpRequest);
        AuthConfigValidationResponse result = authConfigService.validateIdpConnectivity(
                issuerUri, jwksUri, adminIdentity, sourceIp);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/admin/auth-config/discover — Auto-discover OIDC endpoints from issuer URI.
     * Fetches .well-known/openid-configuration and extracts all endpoints.
     */
    @PostMapping("/discover")
    public ResponseEntity<AuthConfigDiscoveryResponse> discoverEndpoints(
            @RequestBody Map<String, String> body) {
        String issuerUri = body.get("issuerUri");
        if (issuerUri == null || issuerUri.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        AuthConfigDiscoveryResponse result = authConfigService.discoverOidcEndpoints(issuerUri);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/admin/auth-config/refresh-jwks — Force JWKS key refresh.
     * Rebuilds the active JwtDecoder with fresh keys from the JWKS endpoint.
     */
    @PostMapping("/refresh-jwks")
    public ResponseEntity<Map<String, String>> refreshJwks(HttpServletRequest httpRequest) {
        String adminIdentity = resolveAdminIdentity(httpRequest);
        String sourceIp = resolveClientIp(httpRequest);
        authConfigService.refreshJwks(adminIdentity, sourceIp);
        return ResponseEntity.ok(Map.of("status", "refreshed", "message", "JWKS keys refreshed successfully"));
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    private String resolveAdminIdentity(HttpServletRequest request) {
        // Try JWT claims (if admin is authenticated via OAuth2)
        Object subject = request.getAttribute("jwt.subject");
        Object username = request.getAttribute("jwt.preferred_username");
        if (username != null) return username.toString();
        if (subject != null) return subject.toString();
        // Fallback to remote addr
        return "admin@" + resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private Map<String, String> errorMap(String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("status", "error");
        error.put("message", message);
        return error;
    }
}
