package com.ws.wsAgenticSecurityGateway.wsServer.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ws.wsAgenticSecurityGateway.agentRegistry.event.BlockedSessionEvent;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayNhiEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentCapabilityFilterService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService.AgentBlockedException;
import com.ws.wsAgenticSecurityGateway.audit.error.McpErrorCode;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full MCP audit filter for HTTP Streamable transport mode.
 *
 * <p>
 * The MCP SDK ({@code HttpServletStreamableServerTransportProvider}) handles
 * {@code initialize}, {@code tools/list}, {@code prompts/list},
 * {@code resources/list},
 * and session DELETE internally with <em>no lifecycle hooks or callbacks</em>.
 * This filter intercepts ALL MCP requests at the HTTP layer to provide full
 * audit parity with stdio mode.
 *
 * <p>
 * <strong>How it works:</strong>
 * <ol>
 * <li>Wraps POST requests in {@link ContentCachingRequestWrapper} to re-read
 * the body
 * after the SDK processes it</li>
 * <li>Lets the SDK handle the request normally via
 * {@code chain.doFilter()}</li>
 * <li>After SDK processing, parses the cached request body to determine the
 * JSON-RPC
 * method and dispatches to the appropriate audit handler</li>
 * <li>Intercepts DELETE requests for session disconnect audit</li>
 * </ol>
 *
 * <p>
 * <strong>Probe filtering:</strong> The {@code mcp-remote} npm bridge creates
 * test/internal sessions ({@code mcp-remote-fallback-test},
 * {@code local-agent-mode-*})
 * that are NOT real agents. These are filtered from agent registration.
 */
@Slf4j
public class HttpMcpAuditFilter implements Filter {

    private final AgentRegistryService agentRegistryService;
    private final AgentCapabilityFilterService capabilityFilterService;
    private final McpAuditService auditService;
    private final CapabilityRegistryService registryService;
    private final ObjectMapper objectMapper;
    private final TokenClassificationService tokenClassificationService;

    /**
     * Tracks which sessions have been registered (prevents duplicate registration).
     */
    private final ConcurrentHashMap<String, Boolean> registeredSessions = new ConcurrentHashMap<>();

    /**
     * Tracks ALL session IDs created by the SDK (including probes) — used for stale
     * session detection.
     */
    private final Set<String> knownSessionIds = ConcurrentHashMap.newKeySet();

    /** Maps sessionId → agentName for use in list/disconnect audit calls. */
    private final ConcurrentHashMap<String, String> sessionAgentNames = new ConcurrentHashMap<>();

    /** Sessions explicitly rejected due to blocked agent policy. */
    private final Set<String> blockedSessionIds = ConcurrentHashMap.newKeySet();

    /** Maps sessionId → founding JWT subject for session-identity binding. */
    private final ConcurrentHashMap<String, String> sessionIdentityCache = new ConcurrentHashMap<>();

    /** Agent names from mcp-remote that are probes/tests, not real agents. */
    private static final Set<String> PROBE_NAMES = Set.of("mcp-remote-fallback-test");

    /** Capability list methods that require response filtering. */
    private static final Set<String> LIST_METHODS = Set.of(
            "tools/list", "prompts/list", "resources/list", "resources/templates/list");

    public HttpMcpAuditFilter(AgentRegistryService agentRegistryService,
            AgentCapabilityFilterService capabilityFilterService,
            McpAuditService auditService,
            CapabilityRegistryService registryService,
            ObjectMapper objectMapper,
            TokenClassificationService tokenClassificationService) {
        this.agentRegistryService = agentRegistryService;
        this.capabilityFilterService = capabilityFilterService;
        this.auditService = auditService;
        this.registryService = registryService;
        this.objectMapper = objectMapper;
        this.tokenClassificationService = tokenClassificationService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String httpMethod = httpRequest.getMethod();

        // ── Handle DELETE (session disconnect) ──────────────────────
        if ("DELETE".equalsIgnoreCase(httpMethod)) {
            handleDelete(httpRequest, httpResponse, chain);
            return;
        }

        // ── Session validation: reject stale/unknown sessions ──────
        // If Mcp-Session-Id header is present but not in our tracked sessions,
        // the session is stale (e.g., from before a gateway restart).
        // Return HTTP 200 + JSON-RPC error so the agent receives a proper
        // error response instead of hanging until timeout.
        String existingSessionId = httpRequest.getHeader("Mcp-Session-Id");
        if (existingSessionId != null && !knownSessionIds.contains(existingSessionId)) {
            log.warn("Rejecting request with stale session ID: {} — gateway was restarted, agent must reconnect",
                    existingSessionId);
            rejectStaleSession(httpRequest, httpResponse, existingSessionId);
            return;
        }

        // ── Only process POST requests (JSON-RPC messages) ─────────
        if (!"POST".equalsIgnoreCase(httpMethod)) {
            chain.doFilter(request, response);
            return;
        }

        // ── Wrap request with cached body so we can inspect before AND after SDK ───
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(httpRequest);

        JsonNode requestJson = parseRequestJson(wrappedRequest.getCachedBody());
        String requestIdRaw = extractRequestIdRaw(requestJson);
        String requestId = extractRequestId(requestJson);
        String mcpMethod = requestJson != null ? requestJson.path("method").asText("") : "";

        // ── Block requests from sessions flagged as blocked ──────────
        String sessionId = wrappedRequest.getHeader("Mcp-Session-Id");
        if (sessionId != null && blockedSessionIds.contains(sessionId)) {
            String blockedAgentName = resolveAgentName(sessionId);
            auditService.auditAgentConnectionRejected(
                    sessionId,
                    requestId,
                    blockedAgentName,
                    null,
                    mcpMethod,
                    "HTTP",
                    "Blocked session attempted request; reconnect after admin approval.");
            rejectBlocked(httpResponse, requestIdRaw, McpErrorCode.AGENT_BLOCKED,
                    "Agent '" + (blockedAgentName != null ? blockedAgentName : "unknown") + "' is blocked by admin. Reconnect after approval.");
            return;
        }

        // ── Block requests from human users who have been blocked by admin ──
        if (sessionId != null) {
            Optional<String> humanBlockReason = agentRegistryService.getHumanBlockReason(sessionId);
            if (humanBlockReason.isPresent()) {
                String blockedAgentName = resolveAgentName(sessionId);
                String humanUsername = agentRegistryService.getHumanUsername(sessionId);
                String reason = humanBlockReason.get();
                log.warn("🚫 Request rejected — human user '{}' behind session {} is BLOCKED: {}",
                        humanUsername, sessionId, reason);
                auditService.auditHumanConnectionRejected(sessionId, requestId,
                        humanUsername, null, blockedAgentName, mcpMethod,
                        "Human user '" + (humanUsername != null ? humanUsername : "unknown") + "' is BLOCKED: " + reason);
                rejectBlocked(httpResponse, requestIdRaw, McpErrorCode.HUMAN_BLOCKED,
                        "Your account '" + (humanUsername != null ? humanUsername : "unknown")
                                + "' has been blocked by an administrator. Reason: " + reason
                                + ". Contact your gateway administrator to request access restoration.");
                return;
            }
        }

        // ── Block requests from NHIs that have been blocked by admin ──
        if (sessionId != null) {
            Optional<String> nhiBlockReason = agentRegistryService.getNhiBlockReason(sessionId);
            if (nhiBlockReason.isPresent()) {
                String blockedAgentName = resolveAgentName(sessionId);
                String nhiName = agentRegistryService.getNhiServiceName(sessionId);
                String reason = nhiBlockReason.get();
                log.warn("🚫 Request rejected — NHI '{}' behind session {} is BLOCKED: {}",
                        nhiName, sessionId, reason);
                auditService.auditNhiConnectionRejected(sessionId, requestId,
                        nhiName, null, null, blockedAgentName, mcpMethod,
                        "Service identity '" + (nhiName != null ? nhiName : "unknown") + "' is BLOCKED: " + reason);
                rejectBlocked(httpResponse, requestIdRaw, McpErrorCode.NHI_BLOCKED,
                        "Service identity '" + (nhiName != null ? nhiName : "unknown")
                                + "' has been blocked by an administrator. Reason: " + reason
                                + ". Contact your gateway administrator to request access restoration.");
                return;
            }
        }

        // ── Session-identity binding: reject identity switch mid-session ──
        if (sessionId != null && !"initialize".equals(mcpMethod)) {
            String jwtSubject = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_SUBJECT);
            String foundingSub = sessionIdentityCache.get(sessionId);
            if (foundingSub != null && jwtSubject != null && !foundingSub.equals(jwtSubject)) {
                String mismatchAgentName = resolveAgentName(sessionId);
                String currentClientId = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID);
                String currentUsername = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_PREFERRED_USERNAME);
                String currentEmail = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_EMAIL);
                String currentTokenType = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_TOKEN_TYPE);
                String currentIssuer = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_ISSUER);
                String xForwardedFor = wrappedRequest.getHeader("X-Forwarded-For");
                String remoteAddr = xForwardedFor != null ? xForwardedFor.split(",")[0].trim() : wrappedRequest.getRemoteAddr();
                String userAgent = wrappedRequest.getHeader("User-Agent");
                @SuppressWarnings("unchecked")
                java.util.List<String> currentRoles = (java.util.List<String>) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_ALL_ROLES);

                log.warn("SESSION IDENTITY MISMATCH on session {}: founding={}, intruder={}, ip={}, clientId={}, tokenType={}",
                        sessionId, foundingSub, jwtSubject, remoteAddr, currentClientId, currentTokenType);
                auditService.auditSessionIdentityMismatch(
                        sessionId, mismatchAgentName, foundingSub, jwtSubject,
                        currentClientId, currentUsername, currentEmail, currentTokenType,
                        currentIssuer, currentRoles, remoteAddr, userAgent, requestId);
                response.setContentType("application/json");
                ((HttpServletResponse) response).setStatus(200);
                response.getWriter().write(
                        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Session bound to different identity — reconnect required\"},\"id\":"
                                + requestIdRaw + "}");
                return;
            }
        }

        // ── Pre-initialize approval gate (must run BEFORE SDK creates session) ───
        if ("initialize".equals(mcpMethod) && requestJson != null) {
            JsonNode clientInfoNode = requestJson.path("params").path("clientInfo");
            String agentName = clientInfoNode.path("name").asText("unknown");
            String agentVersion = clientInfoNode.path("version").asText(null);

            // Also check by JWT client_id (cryptographically verified identity)
            String preAuthClientId = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID);

            // Probes are allowed through (handled separately in registration logic).
            boolean blocked = false;
            if (!isProbeAgent(agentName)) {
                // Check by verified OAuth2 client_id first, then by self-reported name+version
                if (preAuthClientId != null) {
                    blocked = agentRegistryService.isAgentBlocked(preAuthClientId, null);
                }
                if (!blocked) {
                    blocked = agentRegistryService.isAgentBlocked(agentName, agentVersion);
                }
            }
            if (blocked) {
                String displayName = preAuthClientId != null ? preAuthClientId : agentName;
                log.warn("🚫 Pre-initialize rejection for BLOCKED agent: {} v{} (authClientId={})",
                        agentName, agentVersion, preAuthClientId);
                auditService.auditAgentConnectionRejected(
                        null, requestId, displayName, agentVersion,
                        "initialize", "HTTP",
                        "Agent rejected during initialize because profile is BLOCKED.");
                rejectBlocked(httpResponse, requestIdRaw, McpErrorCode.AGENT_BLOCKED,
                        "Agent '" + displayName + "' is blocked by admin. Contact your gateway administrator.");
                return;
            }

            // Check if the human user behind this token is blocked
            String jwtSubject = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_SUBJECT);
            String tokenType = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_TOKEN_TYPE);
            if ("HUMAN_DELEGATED".equals(tokenType) && jwtSubject != null
                    && agentRegistryService.isHumanBlockedBySubject(jwtSubject)) {
                String humanUsername = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_PREFERRED_USERNAME);
                String displayUsername = humanUsername != null ? humanUsername : jwtSubject;
                // Look up blocked reason from DB
                String blockReason = agentRegistryService.getHumanBlockReasonBySubject(jwtSubject);
                log.warn("🚫 Pre-initialize rejection — human user BLOCKED: {} (sub={}) reason={}",
                        displayUsername, jwtSubject, blockReason);
                auditService.auditHumanConnectionRejected(null, requestId,
                        displayUsername, jwtSubject,
                        preAuthClientId != null ? preAuthClientId : agentName,
                        "initialize",
                        "Human user '" + displayUsername + "' is BLOCKED: " + blockReason);
                rejectBlocked(httpResponse, requestIdRaw, McpErrorCode.HUMAN_BLOCKED,
                        "Your account '" + displayUsername + "' has been blocked by an administrator. Reason: "
                                + blockReason + ". Contact your gateway administrator to request access restoration.");
                return;
            }

            // Check if the NHI behind this token is blocked
            if (GatewayOAuth2Filter.TOKEN_TYPE_AUTOMATED.equals(tokenType) && jwtSubject != null
                    && agentRegistryService.isNhiBlockedBySubject(jwtSubject)) {
                String nhiClientId = preAuthClientId;
                String blockReason = agentRegistryService.getNhiBlockReasonBySubject(jwtSubject);
                log.warn("🚫 Pre-initialize rejection — NHI BLOCKED: sub={}, reason={}", jwtSubject, blockReason);
                auditService.auditNhiConnectionRejected(null, requestId,
                        nhiClientId, nhiClientId, jwtSubject,
                        agentName, "initialize",
                        "Service identity (sub=" + jwtSubject + ") is BLOCKED: " + blockReason);
                rejectBlocked(httpResponse, requestIdRaw, McpErrorCode.NHI_BLOCKED,
                        "Service identity '" + (nhiClientId != null ? nhiClientId : jwtSubject)
                                + "' has been blocked by an administrator. Reason: " + blockReason
                                + ". Contact your gateway administrator to request access restoration.");
                return;
            }
        }

        long startTime = System.currentTimeMillis();

        // ── Capability filtering: wrap response for list methods ────
        // For tools/list, prompts/list, resources/list — intercept the response
        // to filter capabilities based on the agent's assigned profiles.
        boolean shouldFilterResponse = LIST_METHODS.contains(mcpMethod) && sessionId != null;
        UUID filterAgentId = null;
        if (shouldFilterResponse) {
            filterAgentId = capabilityFilterService.resolveAgentId(sessionId);
            // Only filter if the agent exists in the registry
            shouldFilterResponse = (filterAgentId != null);
        }

        if (shouldFilterResponse) {
            ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpResponse);

            // Let SDK handle and cache the response
            chain.doFilter(wrappedRequest, responseWrapper);

            long durationMs = System.currentTimeMillis() - startTime;

            // Filter the cached response body
            filterListResponse(responseWrapper, httpResponse, filterAgentId, mcpMethod);

            // After filtering — audit
            afterSdkProcessing(wrappedRequest, httpResponse, durationMs);
        } else {
            // No filtering needed — pass through normally
            chain.doFilter(wrappedRequest, httpResponse);

            long durationMs = System.currentTimeMillis() - startTime;

            // After SDK processing — audit based on method
            afterSdkProcessing(wrappedRequest, httpResponse, durationMs);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // POST-PROCESSING — parse cached request body and dispatch audit
    // ════════════════════════════════════════════════════════════════════

    private void afterSdkProcessing(CachedBodyHttpServletRequest wrappedRequest,
            HttpServletResponse httpResponse,
            long durationMs) {
        try {
            byte[] body = wrappedRequest.getCachedBody();
            if (body.length == 0)
                return;

            JsonNode json = objectMapper.readTree(body);
            String mcpMethod = json.path("method").asText("");
            String requestId = json.has("id") ? json.get("id").asText() : null;

            // Session ID: from response header (initialize creates it) or request header
            String sessionId = httpResponse.getHeader("Mcp-Session-Id");
            if (sessionId == null) {
                sessionId = wrappedRequest.getHeader("Mcp-Session-Id");
            }
            if (sessionId == null)
                return;

            switch (mcpMethod) {
                case "initialize" -> handleInitialize(json, sessionId, requestId, wrappedRequest);
                case "tools/list" -> handleToolsList(sessionId, requestId, durationMs);
                case "prompts/list" -> handlePromptsList(sessionId, requestId, durationMs);
                case "resources/list" -> handleResourcesList(sessionId, requestId, durationMs);
                case "notifications/initialized" -> {
                    // SDK internal handshake — no audit needed
                }
                default -> {
                    // Other notifications (no id field = notification per JSON-RPC)
                    if (requestId == null && !mcpMethod.isEmpty()) {
                        handleNotification(sessionId, mcpMethod, json.path("params"));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error in HTTP MCP audit post-processing: {}", e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // INITIALIZE — agent registration + audit
    // ════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void handleInitialize(JsonNode json, String sessionId, String requestId,
                                  HttpServletRequest httpRequest) {
        // Track ALL sessions (including probes) for stale session detection
        knownSessionIds.add(sessionId);

        // ── Extract JWT attributes (set by GatewayOAuth2Filter at Order 1) ───
        String authClientId = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID);
        String jwtSubject = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_SUBJECT);
        String tokenType = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_TOKEN_TYPE);
        String authMethod = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_AUTH_METHOD);
        String preferredUsername = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_PREFERRED_USERNAME);
        String userEmail = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_EMAIL);
        String userFullName = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_FULL_NAME);
        String userGivenName = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_GIVEN_NAME);
        String userFamilyName = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_FAMILY_NAME);
        Boolean emailVerified = (Boolean) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_EMAIL_VERIFIED);
        String idpIssuer = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_ISSUER);
        List<String> realmRoles = (List<String>) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_REALM_ROLES);
        List<String> clientRoles = (List<String>) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ROLES);
        @SuppressWarnings("unchecked")
        List<String> allRoles = (List<String>) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_ALL_ROLES);
        Map<String, Object> customClaims = (Map<String, Object>) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_CUSTOM_CLAIMS);
        Map<String, Object> rawJwtClaims = (Map<String, Object>) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_RAW_CLAIMS);

        // ── Extract client IP (X-Forwarded-For aware for proxied deployments) ──
        String xForwardedFor = httpRequest.getHeader("X-Forwarded-For");
        String clientIp = xForwardedFor != null ? xForwardedFor.split(",")[0].trim() : httpRequest.getRemoteAddr();

        // Extract agent name early — BEFORE any early returns — so every session
        // in knownSessionIds also has an entry in sessionAgentNames.
        String earlyAgentName = "unknown";
        try {
            earlyAgentName = json.path("params").path("clientInfo").path("name").asText("unknown");
        } catch (Exception ignored) {
        }
        // Use self-reported MCP clientInfo name as display name (e.g., "Anthropic/Toolbox")
        // authClientId (e.g., "claude-desktop") is stored separately in agentClientId for verified identity
        String resolvedAgentName = earlyAgentName;
        sessionAgentNames.put(sessionId, resolvedAgentName);

        if (registeredSessions.containsKey(sessionId))
            return;
        if (registeredSessions.putIfAbsent(sessionId, Boolean.TRUE) != null)
            return;

        String agentName = earlyAgentName;
        String agentVersion = null;
        String protocolVersion = null;

        try {
            JsonNode params = json.path("params");
            JsonNode clientInfoNode = params.path("clientInfo");
            agentName = clientInfoNode.path("name").asText("unknown");
            agentVersion = clientInfoNode.path("version").asText(null);
            protocolVersion = params.path("protocolVersion").asText(null);
            JsonNode capabilities = params.path("capabilities");

            // ── Probe filtering ──────────────────────────────────────
            if (isProbeAgent(agentName)) {
                log.debug("Skipping probe/test agent registration: {} (session={})", agentName, sessionId);
                registeredSessions.remove(sessionId);
                return;
            }

            // ── Tier 1: Token Introspection (once per session) ─────────
            // If mode=introspect and introspection returns a different classification
            // than the JWT signal chain (Tier 2), the introspection result wins.
            String accessToken = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_ACCESS_TOKEN);
            String jwtSignal = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_CLASSIFICATION_SIGNAL);

            if (accessToken != null && tokenType != null) {
                TokenClassificationService.ClassificationResult introspectionResult =
                        tokenClassificationService.classifyViaIntrospection(accessToken);

                if (introspectionResult != null && !introspectionResult.tokenType().equals(tokenType)) {
                    log.info("🔄 Tier 1 introspection overrides Tier 2: {} ({}) → {} ({})",
                            tokenType, jwtSignal, introspectionResult.tokenType(), introspectionResult.matchedSignal());
                    auditService.auditTokenClassificationOverride(
                            sessionId, resolvedAgentName,
                            tokenType, jwtSignal,
                            introspectionResult.tokenType(), introspectionResult.matchedSignal(),
                            requestId);
                    tokenType = introspectionResult.tokenType();
                }

                // Cache final classification for this session
                TokenClassificationService.ClassificationResult finalResult =
                        introspectionResult != null ? introspectionResult :
                        new TokenClassificationService.ClassificationResult(tokenType, jwtSignal);
                tokenClassificationService.cacheClassification(sessionId, finalResult);
            }

            // Agent Registry — discover (upsert) with OAuth2 identity context
            GatewayAgentEntity agent = agentRegistryService.discoverAgent(
                    agentName, agentVersion, protocolVersion,
                    capabilities.isMissingNode() || capabilities.isEmpty() ? null : capabilities,
                    authClientId, tokenType);

            // ── Human user discovery (for HUMAN_DELEGATED tokens) ────
            UUID humanUserId = null;
            if (GatewayOAuth2Filter.TOKEN_TYPE_HUMAN.equals(tokenType) && jwtSubject != null) {
                GatewayHumanUserEntity humanUser = agentRegistryService.discoverHumanUser(
                        jwtSubject, preferredUsername, userEmail,
                        userFullName, userGivenName, userFamilyName,
                        idpIssuer, emailVerified,
                        realmRoles, clientRoles, customClaims, rawJwtClaims, clientIp);
                humanUserId = humanUser.getId();
                agentRegistryService.incrementHumanSessionCount(humanUserId);
                log.info("👤 Human-delegated session: user={} linked to agent={}",
                        preferredUsername, authClientId != null ? authClientId : agentName);
            }

            // ── NHI discovery (for AUTOMATED_AGENT tokens) ────
            UUID nhiId = null;
            if (GatewayOAuth2Filter.TOKEN_TYPE_AUTOMATED.equals(tokenType) && jwtSubject != null) {
                GatewayNhiEntity nhi = agentRegistryService.discoverNhi(
                        jwtSubject, authClientId, idpIssuer,
                        realmRoles, clientRoles, customClaims, rawJwtClaims, clientIp);
                if (nhi != null) {
                    nhiId = nhi.getId();
                    agentRegistryService.incrementNhiSessionCount(nhiId);
                    log.info("🤖 Automated-agent session: NHI={} (sub={}) linked to agent={}",
                            nhi.getServiceName(), jwtSubject,
                            authClientId != null ? authClientId : agentName);

                    // Check if this NHI is blocked
                    if ("BLOCKED".equals(nhi.getStatus())) {
                        log.warn("🚫 NHI is BLOCKED: {} (sub={})", nhi.getServiceName(), jwtSubject);
                        blockedSessionIds.add(sessionId);
                        auditService.auditAgentConnectionRejected(
                                sessionId, requestId,
                                authClientId != null ? authClientId : agentName,
                                agentVersion, "initialize", "HTTP",
                                "Non-Human Identity '" + nhi.getServiceName() + "' is BLOCKED by admin.");
                    }
                }
            }

            // Register session with full OAuth2 context (human + NHI identity)
            agentRegistryService.registerSession(
                    agent.getId(), sessionId,
                    authMethod != null ? authMethod : "HTTP",
                    jwtSubject,
                    tokenType, humanUserId, nhiId, clientIp);

            // Register identity context for audit enrichment — every future audit log for this session
            // will automatically carry tokenType, userIdentity, humanUserId, nhiId, auth fields
            auditService.registerSessionIdentity(sessionId,
                    new McpAuditService.AuditIdentityContext(
                            tokenType,
                            preferredUsername,
                            humanUserId != null ? humanUserId.toString() : null,
                            authMethod != null ? authMethod : "HTTP",
                            jwtSubject,
                            authClientId,
                            allRoles,
                            nhiId != null ? nhiId.toString() : null,
                            clientIp));

            // ── Session-identity binding: store founding JWT subject ────
            if (jwtSubject != null) {
                sessionIdentityCache.put(sessionId, jwtSubject);
            }

            // ── Layer 1: Active Session Replacement ──────────────────
            int replaced = agentRegistryService.disconnectExistingSessionsForAgent(
                    agent.getId(), sessionId);
            if (replaced > 0) {
                log.info("♻️ Replaced {} stale session(s) for agent {} on reconnect",
                        replaced, resolvedAgentName);
                final String currentAgentName = resolvedAgentName;
                registeredSessions.entrySet().removeIf(entry -> !entry.getKey().equals(sessionId) &&
                        currentAgentName.equals(sessionAgentNames.get(entry.getKey())));
                knownSessionIds.removeIf(id -> !id.equals(sessionId) &&
                        currentAgentName.equals(sessionAgentNames.get(id)));
                sessionIdentityCache.entrySet().removeIf(entry -> !entry.getKey().equals(sessionId) &&
                        currentAgentName.equals(sessionAgentNames.get(entry.getKey())));
                sessionAgentNames.entrySet().removeIf(entry -> !entry.getKey().equals(sessionId) &&
                        currentAgentName.equals(entry.getValue()));
            }

            // Audit — session initialization
            auditService.auditServerSessionInitialized(
                    sessionId, protocolVersion,
                    agentName + (agentVersion != null ? " v" + agentVersion : ""),
                    capabilities, requestId, resolvedAgentName);

            log.info("════════════════════════════════════════════════");
            log.info("   HTTP AGENT SESSION REGISTERED");
            log.info("════════════════════════════════════════════════");
            log.info("   Session:    {}", sessionId);
            log.info("   Agent:      {} v{}", agentName, agentVersion);
            log.info("   AuthClient: {}", authClientId != null ? authClientId : "(none — mode=none)");
            log.info("   TokenType:  {}", tokenType != null ? tokenType : "(none)");
            log.info("   Human:      {}", preferredUsername != null ? preferredUsername : "(automated)");
            log.info("   Protocol:   {}", protocolVersion);
            if (replaced > 0) {
                log.info("   Replaced:   {} stale session(s)", replaced);
            }
            log.info("════════════════════════════════════════════════");

        } catch (AgentBlockedException blocked) {
            blockedSessionIds.add(sessionId);
            sessionAgentNames.remove(sessionId);
            registeredSessions.remove(sessionId);
            auditService.auditAgentConnectionRejected(
                    sessionId,
                    requestId,
                    authClientId != null ? authClientId : agentName,
                    agentVersion,
                    "initialize",
                    "HTTP",
                    blocked.getMessage());
            log.warn("🚫 Session {} flagged as blocked after initialize: {}", sessionId, blocked.getMessage());
        } catch (Exception e) {
            log.error("Failed to register HTTP agent session {}: {}", sessionId, e.getMessage(), e);
            registeredSessions.remove(sessionId);
            sessionAgentNames.remove(sessionId);
        }
    }

    /**
     * Returns true if the agent name belongs to a probe/test connection
     * created by the mcp-remote npm bridge, not a real AI agent.
     */
    private boolean isProbeAgent(String agentName) {
        if (agentName == null)
            return false;
        if (PROBE_NAMES.contains(agentName))
            return true;
        if (agentName.startsWith("local-agent-mode"))
            return true;
        if (agentName.contains("fallback-test"))
            return true;
        return false;
    }

    // ════════════════════════════════════════════════════════════════════
    // CAPABILITY FILTERING — intercept list responses for per-agent access
    // ════════════════════════════════════════════════════════════════════

    /**
     * Filter the SDK's list response to only include capabilities the agent is
     * allowed to see based on their assigned capability profiles.
     *
     * <p>Reads the cached response body, parses the JSON-RPC result, filters the
     * capabilities array, and writes the modified response to the original response.
     */
    private void filterListResponse(ContentCachingResponseWrapper responseWrapper,
                                      HttpServletResponse originalResponse,
                                      UUID agentId,
                                      String mcpMethod) {
        try {
            byte[] responseBody = responseWrapper.getContentAsByteArray();
            if (responseBody.length == 0) {
                responseWrapper.copyBodyToResponse();
                return;
            }

            // Determine capability type from the method
            String capabilityType;
            String resultArrayField;
            switch (mcpMethod) {
                case "tools/list" -> { capabilityType = "TOOL"; resultArrayField = "tools"; }
                case "prompts/list" -> { capabilityType = "PROMPT"; resultArrayField = "prompts"; }
                case "resources/list", "resources/templates/list" -> {
                    capabilityType = "RESOURCE"; resultArrayField = "resources";
                    if ("resources/templates/list".equals(mcpMethod)) {
                        resultArrayField = "resourceTemplates";
                    }
                }
                default -> {
                    responseWrapper.copyBodyToResponse();
                    return;
                }
            }

            Set<String> allowedNames = capabilityFilterService.getAllowedCapabilities(agentId, capabilityType);

            // HTTP Streamable transport returns SSE format (id:xxx\ndata:{json}\n\n),
            // not raw JSON. Detect and extract the JSON payload accordingly.
            String bodyStr = new String(responseBody, StandardCharsets.UTF_8);
            String trimmed = bodyStr.trim();
            String jsonPayload;
            String sseIdLine = null;

            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                // SSE format — extract id: and data: lines
                String dataPayload = null;
                for (String line : trimmed.split("\n")) {
                    String l = line.trim();
                    if (l.startsWith("id:")) {
                        sseIdLine = l;
                    } else if (l.startsWith("data:")) {
                        dataPayload = l.substring(5);
                    }
                }
                if (dataPayload == null) {
                    responseWrapper.copyBodyToResponse();
                    return;
                }
                jsonPayload = dataPayload;
            } else {
                jsonPayload = trimmed;
            }

            // Parse the JSON-RPC response
            JsonNode responseJson = objectMapper.readTree(jsonPayload);

            // Navigate to result -> tools/prompts/resources array
            JsonNode resultNode = responseJson.path("result");
            if (resultNode.isMissingNode() || !resultNode.has(resultArrayField)) {
                // Not a valid list response — pass through unchanged
                responseWrapper.copyBodyToResponse();
                return;
            }

            ArrayNode itemsArray = (ArrayNode) resultNode.get(resultArrayField);
            ArrayNode filteredArray = objectMapper.createArrayNode();

            for (JsonNode item : itemsArray) {
                String itemName = item.path("name").asText("");
                if (allowedNames.contains(itemName)) {
                    filteredArray.add(item);
                }
            }

            // Replace the array in the result
            ((ObjectNode) resultNode).set(resultArrayField, filteredArray);

            // Write the modified response — reconstruct SSE format if needed
            byte[] filteredBody;
            if (sseIdLine != null) {
                String sseOutput = sseIdLine + "\ndata:" + objectMapper.writeValueAsString(responseJson) + "\n\n";
                filteredBody = sseOutput.getBytes(StandardCharsets.UTF_8);
            } else {
                filteredBody = objectMapper.writeValueAsBytes(responseJson);
            }
            originalResponse.setContentLength(filteredBody.length);
            originalResponse.getOutputStream().write(filteredBody);
            originalResponse.getOutputStream().flush();

            log.debug("Filtered {}: {} → {} items for agent {}",
                    mcpMethod, itemsArray.size(), filteredArray.size(), agentId);

        } catch (Exception e) {
            log.error("Error filtering {} response for agent {}: {}",
                    mcpMethod, agentId, e.getMessage(), e);
            // On error, pass through the original response unchanged
            try {
                responseWrapper.copyBodyToResponse();
            } catch (IOException ioe) {
                log.error("Failed to copy original response body: {}", ioe.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // LIST OPERATIONS — audit using CapabilityRegistryService for counts
    // ════════════════════════════════════════════════════════════════════

    private void handleToolsList(String sessionId, String requestId, long durationMs) {
        String agentName = resolveAgentName(sessionId);
        int toolCount = registryService.getToolDescriptors().size();
        auditService.auditServerToolsListRequested(sessionId, toolCount, durationMs, requestId, agentName);
        log.debug("Audited HTTP tools/list: session={}, count={}, duration={}ms", sessionId, toolCount, durationMs);
    }

    private void handlePromptsList(String sessionId, String requestId, long durationMs) {
        String agentName = resolveAgentName(sessionId);
        int promptCount = registryService.getPromptDescriptors().size();
        auditService.auditServerPromptsListRequested(sessionId, promptCount, durationMs, requestId, agentName);
        log.debug("Audited HTTP prompts/list: session={}, count={}, duration={}ms", sessionId, promptCount, durationMs);
    }

    private void handleResourcesList(String sessionId, String requestId, long durationMs) {
        String agentName = resolveAgentName(sessionId);
        int resourceCount = registryService.getResourceDescriptors().size();
        auditService.auditServerResourcesListRequested(sessionId, resourceCount, durationMs, requestId, agentName);
        log.debug("Audited HTTP resources/list: session={}, count={}, duration={}ms", sessionId, resourceCount,
                durationMs);
    }

    // ════════════════════════════════════════════════════════════════════
    // NOTIFICATIONS
    // ════════════════════════════════════════════════════════════════════

    private void handleNotification(String sessionId, String method, JsonNode params) {
        String agentName = resolveAgentName(sessionId);
        auditService.auditServerNotificationReceived(sessionId, method, params, agentName);
        log.debug("Audited HTTP notification: session={}, method={}", sessionId, method);
    }

    // ════════════════════════════════════════════════════════════════════
    // STALE SESSION — return JSON-RPC error on HTTP 200
    // ════════════════════════════════════════════════════════════════════

    /**
     * Rejects a request with a stale/unknown session ID by returning a proper
     * JSON-RPC error on HTTP 200. This ensures the MCP client receives the error
     * as a response to its pending request instead of hanging until timeout.
     */
    private void rejectStaleSession(HttpServletRequest request, HttpServletResponse response, String existingSessionId)
            throws IOException {
        // Parse request body to extract the full JSON for auditing and the JSON-RPC
        // "id" for a proper error response
        String errorMessage = "Session expired. Gateway was restarted. Please reconnect the AI agent.";
        String requestId = null;
        JsonNode requestJson = null;
        try {
            byte[] body = request.getInputStream().readAllBytes();
            if (body.length > 0) {
                requestJson = objectMapper.readTree(body);
                if (requestJson.has("id")) {
                    requestId = requestJson.get("id").toString(); // raw value (could be number or string)
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse request body for stale session rejection: {}", e.getMessage());
        }

        // Perform the audit log
        String agentName = agentRegistryService.getAgentNameBySessionId(existingSessionId);
        auditService.auditServerRequestRejected(existingSessionId, agentName, requestJson, errorMessage);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"" + errorMessage + "\"},\"id\":"
                        + (requestId != null ? requestId : "null") + "}");
    }

    /**
     * Generic blocked rejection — returns HTTP 200 + JSON-RPC error with identity-specific
     * error code and human-readable message that agents can surface to end users.
     */
    private void rejectBlocked(HttpServletResponse response, String requestIdRaw,
                               McpErrorCode errorCode, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + errorCode.getCode()
                        + ",\"message\":\"" + escapeJson(message) + "\"},\"id\":"
                        + requestIdRaw + "}");
    }

    /** Escape JSON special characters to prevent injection from reason strings. */
    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Public method for proactive session flagging — called via Spring events
     * when admin blocks an identity mid-session.
     */
    public void addBlockedSessionId(String sessionId) {
        if (sessionId != null) {
            blockedSessionIds.add(sessionId);
        }
    }

    /**
     * Spring event listener — fired by services when admin blocks a human/NHI/agent.
     * Flags the session for immediate rejection on next request without circular dependency.
     */
    @org.springframework.context.event.EventListener
    public void onBlockedSessionEvent(BlockedSessionEvent event) {
        addBlockedSessionId(event.sessionId());
        log.info("🚫 Session flagged for blocked {} '{}': {}",
                event.identityType(), event.identityName(), event.sessionId());
    }

    private JsonNode parseRequestJson(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.debug("Could not parse request JSON in HTTP audit filter: {}", e.getMessage());
            return null;
        }
    }

    private String extractRequestIdRaw(JsonNode requestJson) {
        if (requestJson != null && requestJson.has("id")) {
            return requestJson.get("id").toString();
        }
        return "null";
    }

    private String extractRequestId(JsonNode requestJson) {
        if (requestJson != null && requestJson.has("id")) {
            return requestJson.get("id").asText();
        }
        return null;
    }

    /**
     * HTTP request wrapper with re-readable cached body.
     */
    private static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return bais.read();
                }

                @Override
                public boolean isFinished() {
                    return bais.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // No async read support needed for this wrapper.
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // DELETE — session disconnect
    // ════════════════════════════════════════════════════════════════════

    private void handleDelete(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        String sessionId = request.getHeader("Mcp-Session-Id");

        // Let SDK process the DELETE first
        chain.doFilter(request, response);

        // After SDK processing, audit if this was a tracked session
        if (sessionId != null && registeredSessions.containsKey(sessionId)) {
            try {
                String agentName = resolveAgentName(sessionId);

                // Audit disconnect (synchronous — ensure it persists)
                auditService.auditServerSessionDisconnectedSync(sessionId, agentName);

                // Update Agent Registry DB state
                agentRegistryService.disconnectSession(sessionId);

                // Clean up audit identity cache for this session
                auditService.evictSessionIdentity(sessionId);

                // Clean up tracking maps
                registeredSessions.remove(sessionId);
                knownSessionIds.remove(sessionId);
                blockedSessionIds.remove(sessionId);
                sessionAgentNames.remove(sessionId);
                sessionIdentityCache.remove(sessionId);
                tokenClassificationService.evictSession(sessionId);

                log.info("HTTP session disconnected: {} (agent={})", sessionId, agentName);
            } catch (Exception e) {
                log.error("Failed to audit HTTP session disconnect {}: {}", sessionId, e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // AGENT NAME RESOLUTION — in-memory → DB fallback (never "unknown")
    // ════════════════════════════════════════════════════════════════════

    /**
     * Resolves the agent name for a given session ID.
     *
     * <p>Lookup chain:
     * <ol>
     *   <li>In-memory {@code sessionAgentNames} map (fast, covers active sessions)</li>
     *   <li>{@link AgentRegistryService#getAgentNameBySessionId} (DB fallback for
     *       reaped/disconnected sessions)</li>
     * </ol>
     *
     * <p>Falls back to {@code "unresolved"} only if both lookups fail — this should
     * never happen in practice since every session is persisted during initialize.
     */
    private String resolveAgentName(String sessionId) {
        // 1. Fast in-memory lookup (active sessions)
        String name = sessionAgentNames.get(sessionId);
        if (name != null) {
            return name;
        }

        // 2. DB fallback (reaped/disconnected sessions still have persisted records)
        String dbName = agentRegistryService.getAgentNameBySessionId(sessionId);
        if (dbName != null && !"unknown".equals(dbName)) {
            // Cache it for subsequent calls in same session
            sessionAgentNames.putIfAbsent(sessionId, dbName);
            return dbName;
        }

        return "unresolved";
    }
}
