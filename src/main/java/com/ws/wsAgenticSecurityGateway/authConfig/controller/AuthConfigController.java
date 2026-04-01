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

@RestController
@RequestMapping("/api/admin/auth-config")
@Slf4j
public class AuthConfigController {

    private final AuthConfigService authConfigService;

    public AuthConfigController(AuthConfigService authConfigService) {
        this.authConfigService = authConfigService;
    }

    @GetMapping
    public ResponseEntity<AuthConfigResponse> getAuthConfig() {
        return ResponseEntity.ok(authConfigService.getEffectiveConfig());
    }

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

    @PostMapping("/refresh-jwks")
    public ResponseEntity<Map<String, String>> refreshJwks(HttpServletRequest httpRequest) {
        String adminIdentity = resolveAdminIdentity(httpRequest);
        String sourceIp = resolveClientIp(httpRequest);
        authConfigService.refreshJwks(adminIdentity, sourceIp);
        return ResponseEntity.ok(Map.of("status", "refreshed", "message", "JWKS keys refreshed successfully"));
    }

    private String resolveAdminIdentity(HttpServletRequest request) {
        Object subject = request.getAttribute("jwt.subject");
        Object username = request.getAttribute("jwt.preferred_username");
        if (username != null) return username.toString();
        if (subject != null) return subject.toString();
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
