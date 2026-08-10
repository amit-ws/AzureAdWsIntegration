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

        // Grant type is ground truth for how the token was minted. Read both gty (Auth0/Keycloak) and
        // grant_type (ForgeRock/PingAM). client_credentials => machine; interactive / refresh / password
        // / device_code / implicit => human (a client-credentials flow never issues those).
        String grant = firstNonBlank(asString(allClaims.get("gty")), asString(allClaims.get("grant_type")));
        if (grant != null) {
            if (isClientCredentialsGrant(grant)) {
                return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_3_GRANT_CLIENT_CREDENTIALS");
            }
            if (isHumanGrant(grant)) {
                return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_3_GRANT_HUMAN");
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

        // ---- MULTI-IDP STRUCTURAL DETERMINATION --------------------------------------------------------
        // No single claim distinguishes human from NHI across IdPs, so we resolve it from the token's
        // STRUCTURE, strongest evidence first — never from a hand-set per-client label. Order matters: a
        // real user-authentication event beats a name heuristic; a positive machine marker beats "looks
        // human"; "looks human" beats the absence-of-identity backstop.
        String sub = asString(allClaims.get("sub"));
        String iss = asString(allClaims.get("iss"));
        // The username principal (preferred_username / username / ForgeRock subname).
        String username = firstNonBlank(asString(allClaims.get("preferred_username")),
                                        asString(allClaims.get("username")),
                                        asString(allClaims.get("subname")));
        // The client the token was issued to. IdPs disagree on the claim name: azp/client_id (OIDC),
        // appid (Entra v1), cid (Okta), clientId (Keycloak service-account tokens).
        String effectiveClientId = firstNonBlank(
                asString(allClaims.get("azp")), asString(allClaims.get("client_id")),
                asString(allClaims.get("appid")), asString(allClaims.get("cid")),
                asString(allClaims.get("clientId")));
        boolean hasOpenId = scopeContains(allClaims, "openid");   // an OIDC user-authentication flow
        boolean hasHuman = hasHumanIdentity(allClaims);

        // Signal 5 — INTERACTIVE HUMAN: a genuine user-authentication event (auth_time) with a user context
        // is a person, even in an oddly (service-account-)named shared account. Ranked above name heuristics.
        if (allClaims.get("auth_time") != null && (hasOpenId || hasHuman)) {
            return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_5_INTERACTIVE_HUMAN");
        }

        // Signal 6 — POSITIVE MACHINE markers a specific IdP stamps only on workload tokens.
        if (isServiceAccountEmail(sub) || isServiceAccountEmail(iss)) {             // GCP service account
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_6_GCP_SERVICE_ACCOUNT");
        }
        if (isPresent(allClaims.get("xms_mirid"))) {                                // Entra Managed Identity
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_6_AZURE_MANAGED_IDENTITY");
        }
        if (isPresent(allClaims.get("clientHost")) || isPresent(allClaims.get("clientAddress"))) {
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_6_KEYCLOAK_CLIENT_SESSION");
        }
        if (sub != null && sub.equals(iss)) {                                       // self-issued: asserts itself
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_6_SELF_ISSUED");
        }

        // Signal 5 (structural) — the subject IS a service account / the client acting as itself.
        if (isOwnServiceAccount(username, effectiveClientId)
                || isServiceAccountName(username) || isServiceAccountName(sub)) {
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_5_SERVICE_ACCOUNT_PRINCIPAL");
        }
        if (sub != null && sub.equals(effectiveClientId)) {   // Okta sets sub == cid for client-credentials
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_5_SUBJECT_IS_CLIENT");
        }

        // Signal 6 (human context) — user-flow markers a machine (client-credentials) token never carries.
        if (hasOpenId) {                                       // openid scope == a user authenticated
            return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_6_OIDC_SCOPE");
        }
        if (isPresent(allClaims.get("scp"))) {                 // Entra DELEGATED scopes (app-only uses roles)
            return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_6_DELEGATED_SCOPE");
        }
        if (isFederatedSubject(sub)) {                         // e.g. auth0|..., google-oauth2|... (a user id)
            return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_6_FEDERATED_SUBJECT");
        }
        if (username != null && !username.isBlank() && !isServiceAccountName(username)) {
            return new ClassificationResult(TOKEN_TYPE_HUMAN, "SIGNAL_6_HUMAN_USERNAME");
        }

        // Signal 6b — backstop: a client identity with NO human identity at all was not minted for a person.
        if (effectiveClientId != null && !hasHuman) {
            return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "SIGNAL_6B_CLIENT_WITHOUT_HUMAN_IDENTITY");
        }

        // Signal 7 — nothing conclusive: fail SAFE toward HUMAN (treated as a person who must be approved).
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
            String introClientId = asString(result.get("client_id"));
            String introSub = asString(result.get("sub"));
            if (isServiceAccountName(username) || (introSub != null && introSub.equals(introClientId))) {
                return new ClassificationResult(TOKEN_TYPE_AUTOMATED, "TIER1_INTROSPECT_SERVICE_ACCOUNT");
            }
            if (username != null && !username.isBlank() && !isServiceAccountName(username)) {
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

    /** Keycloak (and most OIDC IdPs) name a client's service account "service-account-<clientId>". */
    private static boolean isServiceAccountName(String name) {
        return name != null && name.toLowerCase().startsWith("service-account-");
    }

    /** True when the username is exactly THIS client's own service account — the tightest structural proof
     *  that the token's subject is the client acting as itself (self-issuance), not a distinct human. */
    private static boolean isOwnServiceAccount(String username, String effectiveClientId) {
        return username != null && effectiveClientId != null
                && username.equalsIgnoreCase("service-account-" + effectiveClientId);
    }

    /** A human token carries at least one person-identity marker: contact, a real name, or an interactive
     *  authentication event. Machine (client-credentials) tokens carry none of these. */
    private static boolean hasHumanIdentity(Map<String, Object> claims) {
        return isPresent(claims.get("email"))
                || isPresent(claims.get("given_name"))
                || isPresent(claims.get("family_name"))
                || isPresent(claims.get("name"))
                || claims.get("auth_time") != null; // set only by an interactive user login
    }

    private static boolean isPresent(Object value) {
        return value instanceof String s ? !s.isBlank() : value != null;
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) return v;
            }
        }
        return null;
    }

    /** Human OAuth flows. A client-credentials grant never issues any of these (no user is present). */
    private static boolean isHumanGrant(String value) {
        if (value == null) return false;
        String g = value.toLowerCase();
        return isAuthCodeGrant(g)
                || g.equals("refresh_token")
                || g.equals("password")
                || g.equals("implicit")
                || g.contains("device_code");
    }

    /** True if a "scope"/"scp" claim (space-delimited String or List) contains the target scope. */
    private static boolean scopeContains(Map<String, Object> claims, String target) {
        for (String key : new String[]{"scope", "scp"}) {
            Object v = claims.get(key);
            if (v instanceof String s) {
                for (String part : s.split("[\\s,]+")) {
                    if (part.equalsIgnoreCase(target)) return true;
                }
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null && o.toString().equalsIgnoreCase(target)) return true;
                }
            }
        }
        return false;
    }

    /** GCP service accounts are named "<name>@<project>.iam.gserviceaccount.com" (in sub or iss). */
    private static boolean isServiceAccountEmail(String value) {
        return value != null && value.toLowerCase().endsWith(".iam.gserviceaccount.com");
    }

    /** A federated/social user subject like "auth0|...", "google-oauth2|..." — a person, not a client. */
    private static boolean isFederatedSubject(String sub) {
        return sub != null && sub.contains("|");
    }

    public record ClassificationResult(String tokenType, String matchedSignal) {}

    public record CachedClassification(String tokenType, String matchedSignal, Instant cachedAt) {
        public boolean isExpired(int ttlSeconds) {
            return Instant.now().isAfter(cachedAt.plusSeconds(ttlSeconds));
        }
    }
}
