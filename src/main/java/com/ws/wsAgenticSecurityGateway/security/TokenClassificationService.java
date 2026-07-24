package com.ws.wsAgenticSecurityGateway.security;

import com.ws.wsAgenticSecurityGateway.security.TokenClassificationProperties;
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

@Service
@Slf4j
public class TokenClassificationService {

    public static final String TOKEN_TYPE_AUTOMATED = "AUTOMATED_AGENT";
    public static final String TOKEN_TYPE_HUMAN = "HUMAN_DELEGATED";

    private static final Set<String> HUMAN_AMR_VALUES = Set.of(
            "pwd", "mfa", "otp", "sms", "fpt", "face", "iris", "vbm",
            "pin", "kba", "sc", "tel", "wia", "user"
    );

    private final TokenClassificationProperties props;
    private final RestTemplate restTemplate;

    private final ConcurrentHashMap<String, CachedClassification> sessionCache = new ConcurrentHashMap<>();

    private volatile String resolvedIntrospectionUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String issuerUri;

    public TokenClassificationService(TokenClassificationProperties props) {
        this.props = props;
        this.restTemplate = new RestTemplate();
    }

    public ClassificationResult classifyFromJwtSignals(
            Map<String, Object> allClaims, Map<String, Object> customClaims) {

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

        Object actClaim = allClaims.get("act");
        if (actClaim instanceof Map<?, ?> actMap && actMap.containsKey("sub")) {
            return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_2_ACT_DELEGATION");
        }

        Object gty = allClaims.get("gty");
        if (gty instanceof String gtyStr) {
            if (isClientCredentialsGrant(gtyStr)) {
                return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_3_GTY_CLIENT_CREDENTIALS");
            }
            if (isAuthCodeGrant(gtyStr)) {
                return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_3_GTY_AUTH_CODE");
            }
        }
        Object idtyp = allClaims.get("idtyp");
        if (idtyp instanceof String idtypStr) {
            if ("app".equalsIgnoreCase(idtypStr)) {
                return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_3_IDTYP_APP");
            }
            if ("user".equalsIgnoreCase(idtypStr)) {
                return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_3_IDTYP_USER");
            }
        }

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

        String sub = asString(allClaims.get("sub"));
        String azp = asString(allClaims.get("azp"));
        String clientId = asString(allClaims.get("client_id"));

        if (sub != null && sub.startsWith("service-account-")) {
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_5_SERVICE_ACCOUNT_PREFIX");
        }
        if (sub != null && (sub.equals(azp) || sub.equals(clientId))) {
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_5_SUB_EQUALS_CLIENT");
        }

        String preferredUsername = asString(allClaims.get("preferred_username"));
        if (preferredUsername != null && !preferredUsername.isBlank()
                && !preferredUsername.startsWith("service-account-")) {
            return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_6_PREFERRED_USERNAME");
        }

        return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_7_CONSERVATIVE_DEFAULT");
    }

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

            Boolean active = (Boolean) result.get("active");
            if (!Boolean.TRUE.equals(active)) {
                log.warn("Token introspection reports token inactive");
                return null;
            }

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

    public void cacheClassification(String sessionId, ClassificationResult result) {
        if (sessionId == null || result == null) return;
        sessionCache.put(sessionId, new CachedClassification(
                result.tokenType(), result.matchedSignal(), Instant.now()));
    }

    public void evictSession(String sessionId) {
        if (sessionId != null) {
            sessionCache.remove(sessionId);
        }
    }


    private String resolveIntrospectionEndpoint() {
        if (props.getIntrospectionUri() != null && !props.getIntrospectionUri().isBlank()) {
            return props.getIntrospectionUri();
        }

        if (resolvedIntrospectionUri != null) {
            return resolvedIntrospectionUri;
        }

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

    public record ClassificationResult(String tokenType, String matchedSignal) {}

    public record CachedClassification(String tokenType, String matchedSignal, Instant cachedAt) {
        public boolean isExpired(int ttlSeconds) {
            return Instant.now().isAfter(cachedAt.plusSeconds(ttlSeconds));
        }
    }
}
