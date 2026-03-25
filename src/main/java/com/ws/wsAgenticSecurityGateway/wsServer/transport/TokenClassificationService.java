package com.ws.wsAgenticSecurityGateway.wsServer.transport;

import com.ws.wsAgenticSecurityGateway.wsServer.config.TokenClassificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 3-Tier Token Classification Engine — determines whether a JWT represents
 * a human-delegated or automated agent request.
 *
 * <p><b>Tier 1 — Token Introspection</b> (protocol-level truth, once per session):
 * Calls the IdP's introspection endpoint to get the actual {@code grant_type}.
 * 100% accurate — the IdP knows which OAuth2 flow was used. Admin cannot misconfigure this.
 *
 * <p><b>Tier 2 — JWT Signal Chain</b> (zero latency, every request):
 * Reads 7 signals from JWT claims in priority order. First match wins.
 * Covers Keycloak, Azure AD, Okta, Auth0, and any IdP with custom claims.
 *
 * <p><b>Tier 3 — Conservative Default</b>:
 * If nothing matches → HUMAN_DELEGATED (safer: more restrictions, not fewer).
 *
 * <p>Standards: RFC 7662 (Introspection), RFC 8693 (Token Exchange/act),
 * RFC 8176 (AMR), RFC 9068 (JWT Access Token Profile).
 */
@Service
@Slf4j
public class TokenClassificationService {

    public static final String TOKEN_TYPE_AUTOMATED = "AUTOMATED_AGENT";
    public static final String TOKEN_TYPE_HUMAN = "HUMAN_DELEGATED";

    /** Human-interactive authentication methods per RFC 8176. */
    private static final Set<String> HUMAN_AMR_VALUES = Set.of(
            "pwd", "mfa", "otp", "sms", "fpt", "face", "iris", "vbm",
            "pin", "kba", "sc", "tel", "wia", "user"
    );

    private final TokenClassificationProperties props;
    private final RestTemplate restTemplate;

    /** Session cache: sessionId → CachedClassification (TTL-based). */
    private final ConcurrentHashMap<String, CachedClassification> sessionCache = new ConcurrentHashMap<>();

    /** Lazily resolved introspection endpoint URI. */
    private volatile String resolvedIntrospectionUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String issuerUri;

    public TokenClassificationService(TokenClassificationProperties props) {
        this.props = props;
        this.restTemplate = new RestTemplate();
    }

    // ════════════════════════════════════════════════════════════════════
    //  TIER 2: JWT Signal Chain (zero latency, 7 signals)
    //  Called on EVERY request by GatewayOAuth2Filter
    // ════════════════════════════════════════════════════════════════════

    /**
     * Classifies a token using only JWT claims. No network calls.
     * 7 signals checked in priority order — first match wins.
     *
     * @param allClaims    full JWT claims map (from Jwt.getClaims())
     * @param customClaims ws_gateway_* prefixed claims subset (may be null)
     * @return classification result with tokenType and matched signal name
     */
    public ClassificationResult classifyFromJwtSignals(
            Map<String, Object> allClaims, Map<String, Object> customClaims) {

        // ── Signal 1: Explicit custom claim ws_gateway_token_type ──
        // Highest trust — IdP admin explicitly declared the token type.
        if (customClaims != null) {
            Object explicit = customClaims.get("ws_gateway_token_type");
            if (explicit instanceof String val && !val.isBlank()) {
                String normalized = val.trim().toUpperCase();
                if (TOKEN_TYPE_HUMAN.equals(normalized) || TOKEN_TYPE_AUTOMATED.equals(normalized)) {
                    return new ClassificationResult(normalized, "SIGNAL_1_CUSTOM_CLAIM");
                }
                log.warn("Unknown ws_gateway_token_type value: '{}' — continuing signal chain", val);
            }
        }

        // ── Signal 2: RFC 8693 "act" claim (delegation/impersonation) ──
        // If "act" is present with a sub, a service is acting on behalf of a human.
        Object actClaim = allClaims.get("act");
        if (actClaim instanceof Map<?, ?> actMap && actMap.containsKey("sub")) {
            return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_2_ACT_DELEGATION");
        }

        // ── Signal 3: IdP-specific grant type / identity type claims ──
        // Auth0: "gty" claim
        Object gty = allClaims.get("gty");
        if (gty instanceof String gtyStr) {
            if (isClientCredentialsGrant(gtyStr)) {
                return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_3_GTY_CLIENT_CREDENTIALS");
            }
            if (isAuthCodeGrant(gtyStr)) {
                return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_3_GTY_AUTH_CODE");
            }
        }
        // Azure AD / Entra: "idtyp" claim
        Object idtyp = allClaims.get("idtyp");
        if (idtyp instanceof String idtypStr) {
            if ("app".equalsIgnoreCase(idtypStr)) {
                return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_3_IDTYP_APP");
            }
            if ("user".equalsIgnoreCase(idtypStr)) {
                return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_3_IDTYP_USER");
            }
        }

        // ── Signal 4: RFC 8176 "amr" claim (Authentication Methods References) ──
        // If amr contains human-interactive methods (pwd, mfa, otp, face, etc.) → human.
        Object amr = allClaims.get("amr");
        if (amr instanceof List<?> amrList && !amrList.isEmpty()) {
            boolean hasHumanMethod = amrList.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::toLowerCase)
                    .anyMatch(HUMAN_AMR_VALUES::contains);
            if (hasHumanMethod) {
                return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_4_AMR_HUMAN_AUTH");
            }
        }

        // ── Signal 5: Machine identity signals (RFC 9068 + Keycloak convention) ──
        String sub = asString(allClaims.get("sub"));
        String azp = asString(allClaims.get("azp"));
        String clientId = asString(allClaims.get("client_id"));

        // Keycloak service accounts: sub starts with "service-account-"
        if (sub != null && sub.startsWith("service-account-")) {
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_5_SERVICE_ACCOUNT_PREFIX");
        }
        // RFC 9068: Client Credentials → sub == client_id or azp
        if (sub != null && (sub.equals(azp) || sub.equals(clientId))) {
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_5_SUB_EQUALS_CLIENT");
        }

        // ── Signal 6: preferred_username presence (weakest human signal) ──
        String preferredUsername = asString(allClaims.get("preferred_username"));
        if (preferredUsername != null && !preferredUsername.isBlank()
                && !preferredUsername.startsWith("service-account-")) {
            return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_6_PREFERRED_USERNAME");
        }

        // ── Signal 7: Conservative default → HUMAN_DELEGATED ──
        // Safer: misclassified bot gets tighter restrictions (acceptable).
        // Misclassified human gets fewer restrictions (unacceptable — we avoid this).
        return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_7_CONSERVATIVE_DEFAULT");
    }

    // ════════════════════════════════════════════════════════════════════
    //  TIER 1: Token Introspection (once per session, cached)
    //  Called ONLY during handleInitialize() in HttpMcpAuditFilter
    // ════════════════════════════════════════════════════════════════════

    /**
     * Introspects the access token with the IdP to determine grant_type.
     * Returns null if introspection is not configured, fails, or is unavailable.
     *
     * @param accessToken the raw Bearer token string
     * @return ClassificationResult or null if introspection cannot determine type
     */
    public ClassificationResult classifyViaIntrospection(String accessToken) {
        if (!props.isIntrospectMode()) return null;
        if (!props.isIntrospectionConfigured()) {
            log.debug("Introspection mode enabled but credentials not configured — skipping");
            return null;
        }
        if (accessToken == null || accessToken.isBlank()) return null;

        String introspectionEndpoint = resolveIntrospectionEndpoint();
        if (introspectionEndpoint == null) {
            log.warn("Token introspection endpoint not available — falling back to JWT signals");
            return null;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setBasicAuth(props.getIntrospectionClientId(), props.getIntrospectionClientSecret());

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("token", accessToken);
            body.add("token_type_hint", "access_token");

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    introspectionEndpoint, HttpMethod.POST, entity,
                    (Class<Map<String, Object>>) (Class<?>) Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Token introspection returned non-200: {}", response.getStatusCode());
                return null;
            }

            Map<String, Object> result = response.getBody();

            // Check if token is active
            Boolean active = (Boolean) result.get("active");
            if (!Boolean.TRUE.equals(active)) {
                log.warn("Token introspection reports token inactive");
                return null;
            }

            // ── Extract grant_type — the authoritative signal ──
            String grantType = asString(result.get("grant_type"));
            if (grantType != null) {
                if (isClientCredentialsGrant(grantType)) {
                    return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "TIER1_INTROSPECT_CLIENT_CREDENTIALS");
                }
                if (isAuthCodeGrant(grantType)) {
                    return new ClassificationResult(TOKEN_TYPE_HUMAN, "TIER1_INTROSPECT_AUTH_CODE");
                }
                if (grantType.contains("token-exchange")) {
                    return new ClassificationResult(TOKEN_TYPE_HUMAN, "TIER1_INTROSPECT_TOKEN_EXCHANGE");
                }
                if (grantType.contains("device_code")) {
                    return new ClassificationResult(TOKEN_TYPE_HUMAN, "TIER1_INTROSPECT_DEVICE_CODE");
                }
                log.info("Unknown grant_type from introspection: '{}' — not overriding JWT signal", grantType);
            }

            // Some IdPs don't return grant_type but do return username
            String username = asString(result.get("username"));
            if (username != null && !username.isBlank() && !username.startsWith("service-account-")) {
                return new ClassificationResult(TOKEN_TYPE_HUMAN, "TIER1_INTROSPECT_USERNAME_PRESENT");
            }

            log.debug("Token introspection inconclusive — no grant_type or username in response");
            return null;

        } catch (Exception e) {
            log.warn("Token introspection failed ({}): {} — falling back to JWT signals",
                    introspectionEndpoint, e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  SESSION CACHE — stores final classification per session
    // ════════════════════════════════════════════════════════════════════

    /**
     * Gets cached classification for a session, or null if not cached/expired.
     */
    public CachedClassification getCachedClassification(String sessionId) {
        if (sessionId == null) return null;
        CachedClassification cached = sessionCache.get(sessionId);
        if (cached != null && cached.isExpired(props.getCacheTtl())) {
            sessionCache.remove(sessionId);
            return null;
        }
        return cached;
    }

    /**
     * Caches the final classification result for a session.
     */
    public void cacheClassification(String sessionId, ClassificationResult result) {
        if (sessionId == null || result == null) return;
        sessionCache.put(sessionId, new CachedClassification(
                result.tokenType(), result.matchedSignal(), Instant.now()));
    }

    /**
     * Removes cached classification when session disconnects.
     */
    public void evictSession(String sessionId) {
        if (sessionId != null) {
            sessionCache.remove(sessionId);
        }
    }

    /** Returns cache size for monitoring. */
    public int getCacheSize() {
        return sessionCache.size();
    }

    // ════════════════════════════════════════════════════════════════════
    //  INTROSPECTION ENDPOINT DISCOVERY
    // ════════════════════════════════════════════════════════════════════

    private String resolveIntrospectionEndpoint() {
        // 1. Explicit override from config
        if (props.getIntrospectionUri() != null && !props.getIntrospectionUri().isBlank()) {
            return props.getIntrospectionUri();
        }

        // 2. Cached from prior OIDC discovery
        if (resolvedIntrospectionUri != null) {
            return resolvedIntrospectionUri;
        }

        // 3. Auto-discover from OIDC metadata
        if (issuerUri != null && !issuerUri.isBlank()) {
            try {
                String oidcUrl = issuerUri + "/.well-known/openid-configuration";
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = restTemplate.getForObject(oidcUrl, Map.class);
                if (metadata != null) {
                    Object endpoint = metadata.get("introspection_endpoint");
                    if (endpoint instanceof String epStr && !epStr.isBlank()) {
                        resolvedIntrospectionUri = epStr;
                        log.info("Discovered introspection endpoint from OIDC metadata: {}", epStr);
                        return epStr;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to discover introspection endpoint from OIDC metadata: {}", e.getMessage());
            }
        }

        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    private static boolean isClientCredentialsGrant(String value) {
        return "client_credentials".equalsIgnoreCase(value)
                || "client-credentials".equalsIgnoreCase(value);
    }

    private static boolean isAuthCodeGrant(String value) {
        return "authorization_code".equalsIgnoreCase(value)
                || "authorization-code".equalsIgnoreCase(value);
    }

    private static String asString(Object obj) {
        if (obj instanceof String s) return s;
        if (obj != null) return obj.toString();
        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    //  RESULT TYPES
    // ════════════════════════════════════════════════════════════════════

    /** Result of a classification attempt — tokenType + which signal matched. */
    public record ClassificationResult(String tokenType, String matchedSignal) {}

    /** Cached classification entry with timestamp for TTL expiration. */
    public record CachedClassification(String tokenType, String matchedSignal, Instant cachedAt) {
        public boolean isExpired(int ttlSeconds) {
            return Instant.now().isAfter(cachedAt.plusSeconds(ttlSeconds));
        }
    }
}
