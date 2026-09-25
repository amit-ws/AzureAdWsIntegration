package com.ws.wsAgenticSecurityGateway.security;

import com.ws.wsAgenticSecurityGateway.authConfig.service.AuthConfigService;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class GatewayOAuth2Filter implements Filter {

    public static final String ATTR_CLIENT_ID = "jwt.client_id";
    public static final String ATTR_SUBJECT = "jwt.subject";
    public static final String ATTR_PREFERRED_USERNAME = "jwt.preferred_username";
    public static final String ATTR_EMAIL = "jwt.email";
    public static final String ATTR_FULL_NAME = "jwt.full_name";
    public static final String ATTR_GIVEN_NAME = "jwt.given_name";
    public static final String ATTR_FAMILY_NAME = "jwt.family_name";
    public static final String ATTR_EMAIL_VERIFIED = "jwt.email_verified";
    public static final String ATTR_ISSUER = "jwt.issuer";
    public static final String ATTR_REALM_ROLES = "jwt.realm_roles";
    public static final String ATTR_CLIENT_ROLES = "jwt.client_roles";
    public static final String ATTR_ALL_ROLES = "jwt.all_roles";
    public static final String ATTR_GROUPS = "jwt.groups";
    public static final String ATTR_TOKEN_TYPE = "jwt.token_type";
    public static final String ATTR_AUTH_METHOD = "jwt.auth_method";
    public static final String ATTR_CUSTOM_CLAIMS = "jwt.custom_claims";
    public static final String ATTR_RAW_CLAIMS = "jwt.raw_claims";
    public static final String ATTR_ACCESS_TOKEN = "jwt.access_token";
    public static final String ATTR_CLASSIFICATION_SIGNAL = "jwt.classification_signal";
    /** The inbound token's {@code jti} — read by the honor-time revocation gate. */
    public static final String ATTR_JTI = "jwt.jti";

    public static final String TOKEN_TYPE_AUTOMATED = "AUTOMATED_AGENT";
    public static final String TOKEN_TYPE_HUMAN = "HUMAN_DELEGATED";
    public static final String AUTH_METHOD_OAUTH2 = "OAUTH2";

    private static final String WS_GATEWAY_CLAIM_PREFIX = "ws_gateway_";

    private final GatewayAuditService auditService;
    private final TokenClassificationService tokenClassificationService;
    private final AuthConfigService authConfigService;
    /** Sessions whose auth-success is already audited — a session-bound client re-presents its token every
     *  request, so auditing each is noise (and floods when an agent retries a stale session). Audit once. */
    private final Set<String> authAuditedSessions = ConcurrentHashMap.newKeySet();

    public GatewayOAuth2Filter(GatewayAuditService auditService,
                               TokenClassificationService tokenClassificationService,
                               AuthConfigService authConfigService) {
        this.auditService = auditService;
        this.tokenClassificationService = tokenClassificationService;
        this.authConfigService = authConfigService;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;

        if (!"oauth2".equals(authConfigService.getEffectiveMode())) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            extractAndStoreJwtClaims(request, jwt);
        } else {
            log.warn("No JWT authentication found in SecurityContext for request from {}",
                    request.getRemoteAddr());
        }

        chain.doFilter(servletRequest, servletResponse);
    }

    @SuppressWarnings("unchecked")
    private void extractAndStoreJwtClaims(HttpServletRequest request, Jwt jwt) {
        String clientId = resolveClientId(jwt);
        String subject = jwt.getSubject();
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        String email = jwt.getClaimAsString("email");
        String fullName = jwt.getClaimAsString("name");
        String givenName = jwt.getClaimAsString("given_name");
        String familyName = jwt.getClaimAsString("family_name");
        Boolean emailVerified = jwt.getClaim("email_verified");
        String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;

        request.setAttribute(ATTR_CLIENT_ID, clientId);
        request.setAttribute(ATTR_SUBJECT, subject);
        request.setAttribute(ATTR_PREFERRED_USERNAME, preferredUsername);
        request.setAttribute(ATTR_EMAIL, email);
        request.setAttribute(ATTR_FULL_NAME, fullName);
        request.setAttribute(ATTR_GIVEN_NAME, givenName);
        request.setAttribute(ATTR_FAMILY_NAME, familyName);
        request.setAttribute(ATTR_EMAIL_VERIFIED, emailVerified);
        request.setAttribute(ATTR_ISSUER, issuer);
        request.setAttribute(ATTR_JTI, jwt.getId());

        List<String> realmRoles = extractRealmRoles(jwt);
        List<String> clientRoles = extractClientRoles(jwt, clientId);
        List<String> allRoles = mergeRoles(realmRoles, clientRoles);

        request.setAttribute(ATTR_REALM_ROLES, realmRoles);
        request.setAttribute(ATTR_CLIENT_ROLES, clientRoles);
        request.setAttribute(ATTR_ALL_ROLES, allRoles);
        request.setAttribute(ATTR_GROUPS, extractGroups(jwt));

        Map<String, Object> customClaims = extractCustomClaims(jwt);
        request.setAttribute(ATTR_CUSTOM_CLAIMS, customClaims);

        Map<String, Object> rawClaims = new HashMap<>(jwt.getClaims());
        request.setAttribute(ATTR_RAW_CLAIMS, rawClaims);

        TokenClassificationService.ClassificationResult classification =
                tokenClassificationService.classifyFromJwtSignals(rawClaims, customClaims);
        String tokenType = classification.tokenType();
        request.setAttribute(ATTR_TOKEN_TYPE, tokenType);
        request.setAttribute(ATTR_CLASSIFICATION_SIGNAL, classification.matchedSignal());
        request.setAttribute(ATTR_AUTH_METHOD, AUTH_METHOD_OAUTH2);

        request.setAttribute(ATTR_ACCESS_TOKEN, jwt.getTokenValue());

        log.debug("JWT claims extracted: client_id={}, sub={}, tokenType={} ({}), roles={}, user={}",
                clientId, subject, tokenType, classification.matchedSignal(), allRoles, preferredUsername);

        String sessionId = request.getHeader("Mcp-Session-Id");
        // Audit auth-success ONCE per session — a session-bound client re-presents the same token on every request
        // (and hammers it while retrying a stale session post-restart), so per-request auditing is pure noise.
        // Stateless calls (no session id) are audited each time, since they are genuinely independent.
        boolean stateless = (sessionId == null || sessionId.isBlank());
        if (!stateless && authAuditedSessions.size() > 8192) authAuditedSessions.clear();   // bound the dedup set
        if (stateless || authAuditedSessions.add(sessionId)) {
            auditService.auditOAuth2AuthSuccess(
                    sessionId, clientId, subject, allRoles, tokenType,
                    preferredUsername, rawClaims, null);
        }
    }

    private String resolveClientId(Jwt jwt) {
        String azp = jwt.getClaimAsString("azp");
        if (azp != null && !azp.isBlank()) return azp;

        String clientId = jwt.getClaimAsString("client_id");
        if (clientId != null && !clientId.isBlank()) return clientId;

        List<String> audience = jwt.getAudience();
        if (audience != null && audience.size() == 1) return audience.get(0);

        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
            return roles.stream().map(String::valueOf).collect(Collectors.toList());
        }
        List<String> flatRoles = jwt.getClaimAsStringList("roles");
        if (flatRoles != null) return new ArrayList<>(flatRoles);

        List<String> groups = jwt.getClaimAsStringList("groups");
        if (groups != null) return new ArrayList<>(groups);

        return List.of();
    }

    /**
     * The agent's group memberships from the JWT {@code groups} claim, normalized by stripping the leading
     * '/' so a Keycloak group path {@code "/financial-agents"} matches a policy's
     * {@code principal in AgentGroup::"financial-agents"}.
     */
    private List<String> extractGroups(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("groups");
        if (groups == null) {
            return List.of();
        }
        return groups.stream()
                .filter(g -> g != null && !g.isBlank())
                .map(g -> g.startsWith("/") ? g.substring(1) : g)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<String> extractClientRoles(Jwt jwt, String clientId) {
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess == null || clientId == null) return List.of();

        Object clientAccess = resourceAccess.get(clientId);
        if (clientAccess instanceof Map<?, ?> clientMap) {
            Object roles = clientMap.get("roles");
            if (roles instanceof List<?> roleList) {
                return roleList.stream().map(String::valueOf).collect(Collectors.toList());
            }
        }
        return List.of();
    }

    private List<String> mergeRoles(List<String> realmRoles, List<String> clientRoles) {
        Set<String> merged = new LinkedHashSet<>(realmRoles);
        merged.addAll(clientRoles);
        return new ArrayList<>(merged);
    }

    private Map<String, Object> extractCustomClaims(Jwt jwt) {
        Map<String, Object> customClaims = new HashMap<>();
        for (Map.Entry<String, Object> entry : jwt.getClaims().entrySet()) {
            if (entry.getKey().startsWith(WS_GATEWAY_CLAIM_PREFIX)) {
                customClaims.put(entry.getKey(), entry.getValue());
            }
        }
        return customClaims;
    }
}
