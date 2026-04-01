package com.ws.wsAgenticSecurityGateway.authConfig.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.authConfig.dto.*;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.authConfig.entity.GatewayAuthConfigEntity;
import com.ws.wsAgenticSecurityGateway.authConfig.repository.GatewayAuthConfigRepository;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.wsClient.service.ServerConfigCryptoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class AuthConfigService {

    private static final String SECRET_MASK = "***REDACTED***";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final GatewayAuthConfigRepository repository;
    private final ServerConfigCryptoService cryptoService;
    private final McpAuditService auditService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final RestTemplate trustAllRestTemplate;
    private final AgentRegistryService agentRegistryService;

    @Value("${ws.gateway.auth.mode:none}")
    private String envAuthMode;
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String envIssuerUri;
    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String envJwksUri;

    private final AtomicReference<String> cachedEffectiveMode = new AtomicReference<>("none");
    private final AtomicReference<LocalDateTime> cachedUpdatedAt = new AtomicReference<>();

    private volatile DelegatingJwtDecoder delegatingJwtDecoder;

    public AuthConfigService(GatewayAuthConfigRepository repository,
                             ServerConfigCryptoService cryptoService,
                             McpAuditService auditService,
                             ObjectMapper objectMapper,
                             AgentRegistryService agentRegistryService) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.agentRegistryService = agentRegistryService;
        this.httpClient = buildTrustingHttpClient();
        this.trustAllRestTemplate = buildTrustAllRestTemplate();
    }

    public void setDelegatingJwtDecoder(DelegatingJwtDecoder decoder) {
        this.delegatingJwtDecoder = decoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void initializeOnStartup() {
        Optional<GatewayAuthConfigEntity> dbConfig = repository.findFirstByEnabledTrueOrderByCreatedAtAsc();
        if (dbConfig.isPresent()) {
            GatewayAuthConfigEntity config = dbConfig.get();
            cachedEffectiveMode.set(config.getAuthMode());
            cachedUpdatedAt.set(config.getUpdatedAt());
            applyDecoderFromConfig(config);
            log.info("Auth config loaded from DATABASE: mode={}, issuer={}, idp={}",
                    config.getAuthMode(), config.getIssuerUri(), config.getIdpDisplayName());
            return;
        }

        if (envAuthMode != null && !envAuthMode.isBlank() && !"none".equals(envAuthMode)) {
            cachedEffectiveMode.set(envAuthMode);
            applyDecoderFromEnvVars();
            log.info("Auth config loaded from ENVIRONMENT: mode={}, issuer={}", envAuthMode, envIssuerUri);
            return;
        }

        cachedEffectiveMode.set("none");
        log.info("No auth config found — defaulting to mode=none (open access)");
    }

    public String getEffectiveMode() {
        return cachedEffectiveMode.get();
    }

    @Transactional(readOnly = true)
    public AuthConfigResponse getEffectiveConfig() {
        String tenant = TenantContext.get();
        Optional<GatewayAuthConfigEntity> dbConfig = tenant != null
                ? repository.findFirstByEnabledTrueAndWsTenantNameOrderByCreatedAtAsc(tenant)
                : repository.findFirstByEnabledTrueOrderByCreatedAtAsc();
        if (dbConfig.isPresent()) {
            return toResponse(dbConfig.get(), "database");
        }

        if (envAuthMode != null && !envAuthMode.isBlank() && !"none".equals(envAuthMode)) {
            return AuthConfigResponse.builder()
                    .authMode(envAuthMode)
                    .issuerUri(envIssuerUri)
                    .jwksUri(envJwksUri)
                    .effectiveMode(envAuthMode)
                    .configSource("environment")
                    .jwtDecoderActive("oauth2".equals(envAuthMode))
                    .gracePeriodActive(delegatingJwtDecoder != null && delegatingJwtDecoder.isGracePeriodActive())
                    .gracePeriodRemainingMinutes(delegatingJwtDecoder != null ? delegatingJwtDecoder.getGracePeriodRemainingMinutes() : null)
                    .build();
        }

        return AuthConfigResponse.builder()
                .authMode("none")
                .effectiveMode("none")
                .configSource("default")
                .jwtDecoderActive(false)
                .gracePeriodActive(false)
                .build();
    }

    @Transactional
    public AuthConfigResponse createAuthConfig(AuthConfigRequest request,
                                                String adminIdentity, String sourceIp) {
        String tenant = TenantContext.get();
        Optional<GatewayAuthConfigEntity> existingConfig = repository.findFirstByEnabledTrueAndWsTenantNameOrderByCreatedAtAsc(tenant);
        if (existingConfig.isPresent()) {
            throw new IllegalStateException("Auth config already exists — use update instead");
        }

        String displayName = request.getIdpDisplayName() != null && !request.getIdpDisplayName().isBlank()
                ? request.getIdpDisplayName()
                : detectIdpType(request.getIssuerUri());

        GatewayAuthConfigEntity entity = GatewayAuthConfigEntity.builder()
                .authMode(request.getAuthMode())
                .idpDisplayName(displayName)
                .issuerUri(request.getIssuerUri())
                .jwksUri(request.getJwksUri())
                .authorizationEndpoint(request.getAuthorizationEndpoint())
                .tokenEndpoint(request.getTokenEndpoint())
                .introspectionEndpoint(request.getIntrospectionEndpoint())
                .registrationEndpoint(request.getRegistrationEndpoint())
                .supportedScopes(request.getSupportedScopes())
                .audience(request.getAudience())
                .tokenClassificationMode(request.getTokenClassificationMode())
                .wsTenantName(tenant)
                .introspectionClientId(request.getIntrospectionClientId())
                .introspectionClientSecret(encryptSecret(request.getIntrospectionClientSecret()))
                .gracePeriodMinutes(request.getGracePeriodMinutes())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .build();

        entity = repository.save(entity);
        log.info("Auth config CREATED: mode={}, issuer={}", entity.getAuthMode(), entity.getIssuerUri());

        cachedEffectiveMode.set(entity.getAuthMode());
        cachedUpdatedAt.set(entity.getUpdatedAt());
        applyDecoderFromConfig(entity);

        auditService.auditAuthConfigCreated(entity.getAuthMode(), entity.getIssuerUri(),
                entity.getIdpDisplayName(), entity.getTokenClassificationMode(),
                adminIdentity, sourceIp);

        return toResponse(entity, "database");
    }

    @Transactional
    public AuthConfigResponse updateAuthConfig(AuthConfigRequest request,
                                                String adminIdentity, String sourceIp) {
        GatewayAuthConfigEntity existing = repository.findFirstByWsTenantNameOrderByCreatedAtAsc(TenantContext.get())
                .orElseThrow(() -> new IllegalStateException("No auth config exists — create first"));

        Map<String, Object> changedFields = new LinkedHashMap<>();
        String oldMode = existing.getAuthMode();
        String oldIssuer = existing.getIssuerUri();

        if (!Objects.equals(existing.getAuthMode(), request.getAuthMode())) {
            changedFields.put("authMode", Map.of("old", str(existing.getAuthMode()), "new", str(request.getAuthMode())));
        }
        if (!Objects.equals(existing.getIssuerUri(), request.getIssuerUri())) {
            changedFields.put("issuerUri", Map.of("old", str(existing.getIssuerUri()), "new", str(request.getIssuerUri())));
        }
        if (!Objects.equals(existing.getJwksUri(), request.getJwksUri())) {
            changedFields.put("jwksUri", Map.of("old", str(existing.getJwksUri()), "new", str(request.getJwksUri())));
        }
        if (!Objects.equals(existing.getIdpDisplayName(), request.getIdpDisplayName())) {
            changedFields.put("idpDisplayName", Map.of("old", str(existing.getIdpDisplayName()), "new", str(request.getIdpDisplayName())));
        }
        if (!Objects.equals(existing.getTokenClassificationMode(), request.getTokenClassificationMode())) {
            changedFields.put("tokenClassificationMode", Map.of("old", str(existing.getTokenClassificationMode()), "new", str(request.getTokenClassificationMode())));
        }

        existing.setAuthMode(request.getAuthMode());
        String updatedDisplayName = request.getIdpDisplayName() != null && !request.getIdpDisplayName().isBlank()
                ? request.getIdpDisplayName()
                : detectIdpType(request.getIssuerUri());
        existing.setIdpDisplayName(updatedDisplayName);
        existing.setIssuerUri(request.getIssuerUri());
        existing.setJwksUri(request.getJwksUri());
        existing.setAuthorizationEndpoint(request.getAuthorizationEndpoint());
        existing.setTokenEndpoint(request.getTokenEndpoint());
        existing.setIntrospectionEndpoint(request.getIntrospectionEndpoint());
        existing.setRegistrationEndpoint(request.getRegistrationEndpoint());
        existing.setSupportedScopes(request.getSupportedScopes());
        existing.setAudience(request.getAudience());
        existing.setTokenClassificationMode(request.getTokenClassificationMode());
        existing.setIntrospectionClientId(request.getIntrospectionClientId());
        existing.setGracePeriodMinutes(request.getGracePeriodMinutes());
        if (request.getEnabled() != null) existing.setEnabled(request.getEnabled());

        if (request.getIntrospectionClientSecret() != null
                && !SECRET_MASK.equals(request.getIntrospectionClientSecret())) {
            existing.setIntrospectionClientSecret(encryptSecret(request.getIntrospectionClientSecret()));
            changedFields.put("introspectionClientSecret", "***changed***");
        }

        existing = repository.save(existing);
        log.info("Auth config UPDATED: mode={}, issuer={}, changedFields={}",
                existing.getAuthMode(), existing.getIssuerUri(), changedFields.keySet());

        cachedEffectiveMode.set(existing.getAuthMode());
        cachedUpdatedAt.set(existing.getUpdatedAt());

        boolean issuerChanged = !Objects.equals(oldIssuer, existing.getIssuerUri())
                || !Objects.equals(existing.getJwksUri(), request.getJwksUri());
        boolean modeChanged = !Objects.equals(oldMode, existing.getAuthMode());

        if (modeChanged) {
            applyDecoderFromConfig(existing);
            int activeSessions = agentRegistryService.getConnectedSessions().size();
            auditService.auditAuthModeChanged(oldMode, existing.getAuthMode(),
                    activeSessions, adminIdentity, sourceIp);
        } else if (issuerChanged && "oauth2".equals(existing.getAuthMode())) {
            swapDecoderWithGracePeriod(existing, oldIssuer);
        }

        if (!changedFields.isEmpty()) {
            auditService.auditAuthConfigUpdated(changedFields, adminIdentity, sourceIp);
        }

        return toResponse(existing, "database");
    }

    @Transactional
    public void deleteAuthConfig(String adminIdentity, String sourceIp) {
        GatewayAuthConfigEntity existing = repository.findFirstByWsTenantNameOrderByCreatedAtAsc(TenantContext.get())
                .orElseThrow(() -> new IllegalStateException("No auth config exists to delete"));

        String oldMode = existing.getAuthMode();
        String oldIssuer = existing.getIssuerUri();

        repository.delete(existing);
        log.info("Auth config DELETED — reverting to env vars or defaults");

        if (envAuthMode != null && !envAuthMode.isBlank() && !"none".equals(envAuthMode)) {
            cachedEffectiveMode.set(envAuthMode);
            applyDecoderFromEnvVars();
        } else {
            cachedEffectiveMode.set("none");
            if (delegatingJwtDecoder != null) {
                delegatingJwtDecoder.setDecoder(noOpDecoder());
            }
        }
        cachedUpdatedAt.set(null);

        auditService.auditAuthConfigDeleted(oldMode, oldIssuer, adminIdentity, sourceIp);
        if (!"none".equals(oldMode)) {
            int activeSessions = agentRegistryService.getConnectedSessions().size();
            auditService.auditAuthModeChanged(oldMode, cachedEffectiveMode.get(),
                    activeSessions, adminIdentity, sourceIp);
        }
    }

    public AuthConfigDiscoveryResponse discoverOidcEndpoints(String issuerUri) {
        try {
            String discoveryUrl = issuerUri.endsWith("/")
                    ? issuerUri + ".well-known/openid-configuration"
                    : issuerUri + "/.well-known/openid-configuration";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(discoveryUrl))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return AuthConfigDiscoveryResponse.builder()
                        .issuerUri(issuerUri)
                        .error("OIDC discovery returned HTTP " + response.statusCode())
                        .discoveredAt(LocalDateTime.now())
                        .build();
            }

            JsonNode doc = objectMapper.readTree(response.body());

            String scopes = "openid,email,profile";
            if (doc.has("scopes_supported")) {
                List<String> scopeList = new ArrayList<>();
                doc.get("scopes_supported").forEach(s -> scopeList.add(s.asText()));
                scopes = String.join(",", scopeList);
            }

            return AuthConfigDiscoveryResponse.builder()
                    .issuerUri(issuerUri)
                    .jwksUri(jsonText(doc, "jwks_uri"))
                    .authorizationEndpoint(jsonText(doc, "authorization_endpoint"))
                    .tokenEndpoint(jsonText(doc, "token_endpoint"))
                    .introspectionEndpoint(jsonText(doc, "introspection_endpoint"))
                    .registrationEndpoint(jsonText(doc, "registration_endpoint"))
                    .supportedScopes(scopes)
                    .discoveredAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            String friendlyError = resolveConnectionError(e, issuerUri);
            log.warn("OIDC discovery failed for {} — {}", issuerUri, friendlyError);
            return AuthConfigDiscoveryResponse.builder()
                    .issuerUri(issuerUri)
                    .error(friendlyError)
                    .discoveredAt(LocalDateTime.now())
                    .build();
        }
    }

    public AuthConfigValidationResponse validateIdpConnectivity(String issuerUri, String jwksUri,
                                                                  String adminIdentity, String sourceIp) {
        String effectiveJwksUri = jwksUri;
        if (effectiveJwksUri != null && !effectiveJwksUri.isBlank()) {
            try {
                String issuerHost = URI.create(issuerUri).getHost();
                String jwksHost = URI.create(effectiveJwksUri).getHost();
                if (issuerHost != null && !issuerHost.equalsIgnoreCase(jwksHost)) {
                    log.info("JWKS URI host '{}' does not match issuer host '{}' — re-discovering",
                            jwksHost, issuerHost);
                    effectiveJwksUri = null;
                }
            } catch (Exception e) {
                log.warn("Failed to parse JWKS/issuer URI for host comparison — re-discovering: {}", e.getMessage());
                effectiveJwksUri = null;
            }
        }
        if (effectiveJwksUri == null || effectiveJwksUri.isBlank()) {
            AuthConfigDiscoveryResponse discovery = discoverOidcEndpoints(issuerUri);
            effectiveJwksUri = discovery.getJwksUri();
            if (effectiveJwksUri == null || effectiveJwksUri.isBlank()) {
                auditService.auditAuthConfigValidationFailed(issuerUri,
                        "Could not discover JWKS URI from issuer", 0, 0, adminIdentity, sourceIp);
                return AuthConfigValidationResponse.builder()
                        .issuerUri(issuerUri)
                        .jwksReachable(false)
                        .errorMessage("Could not discover JWKS URI from issuer")
                        .build();
            }
        }

        try {
            long start = System.currentTimeMillis();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(effectiveJwksUri))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                auditService.auditAuthConfigValidationFailed(issuerUri,
                        "JWKS endpoint returned HTTP " + response.statusCode(),
                        response.statusCode(), latencyMs, adminIdentity, sourceIp);
                return AuthConfigValidationResponse.builder()
                        .issuerUri(issuerUri)
                        .jwksUri(effectiveJwksUri)
                        .jwksReachable(false)
                        .jwksLatencyMs(latencyMs)
                        .errorMessage("JWKS endpoint returned HTTP " + response.statusCode())
                        .build();
            }

            JsonNode jwks = objectMapper.readTree(response.body());
            int keyCount = jwks.has("keys") ? jwks.get("keys").size() : 0;

            auditService.auditAuthConfigValidated(issuerUri, true, latencyMs, keyCount,
                    adminIdentity, sourceIp);

            return AuthConfigValidationResponse.builder()
                    .issuerUri(issuerUri)
                    .jwksUri(effectiveJwksUri)
                    .jwksReachable(true)
                    .jwksLatencyMs(latencyMs)
                    .jwksKeyCount(keyCount)
                    .issuerMatchesDiscovery(true)
                    .build();

        } catch (Exception e) {
            String friendlyError = resolveConnectionError(e, effectiveJwksUri);
            log.warn("IdP validation failed for {} — {}", issuerUri, friendlyError);
            auditService.auditAuthConfigValidationFailed(issuerUri,
                    friendlyError, 0, 0, adminIdentity, sourceIp);
            return AuthConfigValidationResponse.builder()
                    .issuerUri(issuerUri)
                    .jwksUri(effectiveJwksUri)
                    .jwksReachable(false)
                    .errorMessage(friendlyError)
                    .build();
        }
    }

    @Transactional(readOnly = true)
    public void refreshJwks(String adminIdentity, String sourceIp) {
        Optional<GatewayAuthConfigEntity> dbConfig = repository.findFirstByEnabledTrueOrderByCreatedAtAsc();
        if (dbConfig.isPresent() && "oauth2".equals(dbConfig.get().getAuthMode())) {
            applyDecoderFromConfig(dbConfig.get());
            auditService.auditAuthJwksRefreshed(dbConfig.get().getIssuerUri(), 0, 0, "manual:" + adminIdentity);
        } else if ("oauth2".equals(envAuthMode)) {
            applyDecoderFromEnvVars();
            auditService.auditAuthJwksRefreshed(envIssuerUri, 0, 0, "manual:" + adminIdentity);
        }
    }

    @Scheduled(fixedDelayString = "${ws.gateway.auth.config-poll-interval-ms:60000}")
    @Transactional(readOnly = true)
    public void pollForConfigChanges() {
        try {
            Optional<GatewayAuthConfigEntity> dbConfig = repository.findFirstByOrderByCreatedAtAsc();
            if (dbConfig.isEmpty()) {
                if (cachedUpdatedAt.get() != null) {
                    log.info("Auth config DB row removed — reverting to env vars or defaults");
                    cachedUpdatedAt.set(null);
                    if (envAuthMode != null && !"none".equals(envAuthMode)) {
                        cachedEffectiveMode.set(envAuthMode);
                        applyDecoderFromEnvVars();
                    } else {
                        cachedEffectiveMode.set("none");
                        if (delegatingJwtDecoder != null) {
                            delegatingJwtDecoder.setDecoder(noOpDecoder());
                        }
                    }
                }
                return;
            }

            GatewayAuthConfigEntity config = dbConfig.get();
            LocalDateTime lastKnown = cachedUpdatedAt.get();

            if (lastKnown == null || (config.getUpdatedAt() != null && config.getUpdatedAt().isAfter(lastKnown))) {
                log.info("Auth config change detected via polling — reloading (updatedAt={})", config.getUpdatedAt());
                cachedEffectiveMode.set(config.getEnabled() ? config.getAuthMode() : "none");
                cachedUpdatedAt.set(config.getUpdatedAt());
                applyDecoderFromConfig(config);
            }
        } catch (Exception e) {
            log.debug("Auth config poll failed (transient): {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<GatewayAuthConfigEntity> getActiveDbConfig() {
        return repository.findFirstByEnabledTrueOrderByCreatedAtAsc();
    }

    public String getEffectiveIssuerUri() {
        Optional<GatewayAuthConfigEntity> dbConfig = repository.findFirstByEnabledTrueOrderByCreatedAtAsc();
        if (dbConfig.isPresent() && dbConfig.get().getIssuerUri() != null) {
            return dbConfig.get().getIssuerUri();
        }
        return envIssuerUri != null && !envIssuerUri.isBlank() ? envIssuerUri : null;
    }

    public String getEffectiveJwksUri() {
        Optional<GatewayAuthConfigEntity> dbConfig = repository.findFirstByEnabledTrueOrderByCreatedAtAsc();
        if (dbConfig.isPresent() && dbConfig.get().getJwksUri() != null) {
            return dbConfig.get().getJwksUri();
        }
        return envJwksUri != null && !envJwksUri.isBlank() ? envJwksUri : null;
    }

    private void applyDecoderFromConfig(GatewayAuthConfigEntity config) {
        if (delegatingJwtDecoder == null) return;
        if (!"oauth2".equals(config.getAuthMode()) || !Boolean.TRUE.equals(config.getEnabled())) {
            delegatingJwtDecoder.setDecoder(noOpDecoder());
            return;
        }
        try {
            String jwksUri = config.getJwksUri();
            if (jwksUri == null || jwksUri.isBlank()) {
                AuthConfigDiscoveryResponse discovery = discoverOidcEndpoints(config.getIssuerUri());
                jwksUri = discovery.getJwksUri();
            }
            if (jwksUri != null && !jwksUri.isBlank()) {
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                        .restOperations(trustAllRestTemplate).build();
                delegatingJwtDecoder.setDecoder(decoder);
                log.info("JwtDecoder activated from DB config: jwksUri={}", jwksUri);
            } else {
                log.warn("Cannot build JwtDecoder — no JWKS URI available");
                delegatingJwtDecoder.setDecoder(noOpDecoder());
            }
        } catch (Exception e) {
            log.error("Failed to build JwtDecoder from DB config: {}", e.getMessage());
            delegatingJwtDecoder.setDecoder(noOpDecoder());
        }
    }

    private void applyDecoderFromEnvVars() {
        if (delegatingJwtDecoder == null) return;
        try {
            String jwksUri = envJwksUri;
            if ((jwksUri == null || jwksUri.isBlank()) && envIssuerUri != null && !envIssuerUri.isBlank()) {
                AuthConfigDiscoveryResponse discovery = discoverOidcEndpoints(envIssuerUri);
                jwksUri = discovery.getJwksUri();
            }
            if (jwksUri != null && !jwksUri.isBlank()) {
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                        .restOperations(trustAllRestTemplate).build();
                delegatingJwtDecoder.setDecoder(decoder);
                log.info("JwtDecoder activated from env vars: jwksUri={}", jwksUri);
            }
        } catch (Exception e) {
            log.error("Failed to build JwtDecoder from env vars: {}", e.getMessage());
        }
    }

    private void swapDecoderWithGracePeriod(GatewayAuthConfigEntity config, String oldIssuer) {
        if (delegatingJwtDecoder == null) return;
        try {
            String jwksUri = config.getJwksUri();
            if (jwksUri == null || jwksUri.isBlank()) {
                AuthConfigDiscoveryResponse discovery = discoverOidcEndpoints(config.getIssuerUri());
                jwksUri = discovery.getJwksUri();
            }
            if (jwksUri != null && !jwksUri.isBlank()) {
                NimbusJwtDecoder newDecoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
                int graceMins = config.getGracePeriodMinutes() != null ? config.getGracePeriodMinutes() : 30;
                delegatingJwtDecoder.swapDecoder(newDecoder, graceMins, oldIssuer);
                int activeSessions = agentRegistryService.getConnectedSessions().size();
                auditService.auditAuthGracePeriodStarted(oldIssuer, config.getIssuerUri(),
                        graceMins, activeSessions);
                log.info("JwtDecoder swapped with {}min grace period (old={}, new={})",
                        graceMins, oldIssuer, config.getIssuerUri());
            }
        } catch (Exception e) {
            log.error("Failed to swap JwtDecoder with grace period: {}", e.getMessage());
        }
    }

    private JwtDecoder noOpDecoder() {
        return token -> {
            throw new org.springframework.security.oauth2.jwt.JwtException(
                    "JWT decoding is disabled (auth.mode=none)");
        };
    }

    private AuthConfigResponse toResponse(GatewayAuthConfigEntity entity, String configSource) {
        return AuthConfigResponse.builder()
                .id(entity.getId())
                .authMode(entity.getAuthMode())
                .idpDisplayName(entity.getIdpDisplayName())
                .issuerUri(entity.getIssuerUri())
                .jwksUri(entity.getJwksUri())
                .authorizationEndpoint(entity.getAuthorizationEndpoint())
                .tokenEndpoint(entity.getTokenEndpoint())
                .introspectionEndpoint(entity.getIntrospectionEndpoint())
                .registrationEndpoint(entity.getRegistrationEndpoint())
                .supportedScopes(entity.getSupportedScopes())
                .audience(entity.getAudience())
                .tokenClassificationMode(entity.getTokenClassificationMode())
                .introspectionClientId(entity.getIntrospectionClientId())
                .introspectionClientSecret(entity.getIntrospectionClientSecret() != null ? SECRET_MASK : null)
                .gracePeriodMinutes(entity.getGracePeriodMinutes())
                .enabled(entity.getEnabled())
                .version(entity.getVersion())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .effectiveMode(cachedEffectiveMode.get())
                .configSource(configSource)
                .jwtDecoderActive("oauth2".equals(cachedEffectiveMode.get()))
                .gracePeriodActive(delegatingJwtDecoder != null && delegatingJwtDecoder.isGracePeriodActive())
                .gracePeriodRemainingMinutes(delegatingJwtDecoder != null ? delegatingJwtDecoder.getGracePeriodRemainingMinutes() : null)
                .build();
    }

    private String encryptSecret(String value) {
        if (value == null || value.isBlank() || SECRET_MASK.equals(value)) return null;
        return cryptoService.encrypt(value);
    }

    private String str(String value) {
        return value != null ? value : "";
    }

    private String detectIdpType(String issuerUri) {
        if (issuerUri == null || issuerUri.isBlank()) return "Unknown IdP";
        String uri = issuerUri.toLowerCase();
        if (uri.contains("keycloak") || uri.contains("/realms/")) return "Keycloak";
        if (uri.contains("login.microsoftonline") || uri.contains("sts.windows.net")) return "Azure AD";
        if (uri.contains(".okta.com")) return "Okta";
        if (uri.contains("auth0.com")) return "Auth0";
        if (uri.contains("accounts.google")) return "Google";
        if (uri.contains("cognito")) return "AWS Cognito";
        if (uri.contains("ping")) return "PingIdentity";
        return "OIDC Provider";
    }

    private String resolveConnectionError(Exception e, String url) {
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();

        String rootClass = root.getClass().getSimpleName();
        String rootMsg = root.getMessage();

        if (root instanceof java.nio.channels.UnresolvedAddressException) {
            String host = url;
            try { host = java.net.URI.create(url).getHost(); } catch (Exception ignored) {}
            return "DNS resolution failed — cannot resolve hostname '" + host + "'. Check if the URL is correct and the IdP is reachable from the gateway's network.";
        }
        if (root instanceof java.net.ConnectException) {
            return "Connection refused — the IdP at '" + url + "' is not accepting connections. Verify the IdP is running and the port is correct.";
        }
        if (root instanceof java.net.SocketTimeoutException || rootClass.contains("Timeout")) {
            return "Connection timed out — the IdP at '" + url + "' did not respond within " + HTTP_TIMEOUT.toSeconds() + " seconds. Check network connectivity and firewall rules.";
        }
        if (root instanceof javax.net.ssl.SSLException || rootClass.contains("SSL")) {
            return "SSL/TLS error — could not establish a secure connection to '" + url + "'. The IdP may use an untrusted certificate. Details: " + (rootMsg != null ? rootMsg : rootClass);
        }
        if (root instanceof java.net.UnknownHostException) {
            return "Unknown host — DNS cannot find '" + (rootMsg != null ? rootMsg : url) + "'. Check the URL spelling and DNS configuration.";
        }
        if (rootMsg != null && !rootMsg.isBlank()) {
            return rootClass + " — " + rootMsg;
        }
        return "Unexpected error (" + rootClass + ") while connecting to '" + url + "'. Check gateway logs for details.";
    }

    private String jsonText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private static RestTemplate buildTrustAllRestTemplate() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new java.security.SecureRandom());

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                    if (connection instanceof HttpsURLConnection httpsConn) {
                        httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
                        httpsConn.setHostnameVerifier((hostname, session) -> true);
                    }
                    super.prepareConnection(connection, httpMethod);
                }
            };
            factory.setConnectTimeout(HTTP_TIMEOUT);
            factory.setReadTimeout(HTTP_TIMEOUT);

            return new RestTemplate(factory);
        } catch (Exception e) {
            log.warn("Failed to build trust-all RestTemplate, using default: {}", e.getMessage());
            return new RestTemplate();
        }
    }

    private static HttpClient buildTrustingHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new java.security.SecureRandom());
            return HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .sslContext(sslContext)
                    .build();
        } catch (Exception e) {
            return HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .build();
        }
    }
}
