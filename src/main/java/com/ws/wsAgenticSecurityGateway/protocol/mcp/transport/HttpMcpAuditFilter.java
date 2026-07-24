package com.ws.wsAgenticSecurityGateway.protocol.mcp.transport;
import com.ws.wsAgenticSecurityGateway.security.GatewayOAuth2Filter;
import com.ws.wsAgenticSecurityGateway.security.TokenClassificationService;

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
import com.ws.wsAgenticSecurityGateway.audit.error.GatewayErrorCode;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.authConfig.repository.GatewayAuthConfigRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class HttpMcpAuditFilter implements Filter {

    private final AgentRegistryService agentRegistryService;
    private final AgentCapabilityFilterService capabilityFilterService;
    private final GatewayAuditService auditService;
    private final CapabilityRegistryService registryService;
    private final GatewayAuthConfigRepository authConfigRepository;
    private final ObjectMapper objectMapper;
    private final TokenClassificationService tokenClassificationService;

    private final ConcurrentHashMap<String, Boolean> registeredSessions = new ConcurrentHashMap<>();

    private final Set<String> knownSessionIds = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, String> sessionAgentNames = new ConcurrentHashMap<>();

    private final Set<String> blockedSessionIds = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, String> sessionIdentityCache = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, String> sessionToTenant = new ConcurrentHashMap<>();

    private static final Set<String> PROBE_NAMES = Set.of("mcp-remote-fallback-test");

    private static final Set<String> LIST_METHODS = Set.of(
            "tools/list", "prompts/list", "resources/list", "resources/templates/list");

    private static final Set<String> EXECUTION_METHODS = Set.of(
            "tools/call", "prompts/get", "resources/read", "resources/templates/read");

    public HttpMcpAuditFilter(AgentRegistryService agentRegistryService,
            AgentCapabilityFilterService capabilityFilterService,
            GatewayAuditService auditService,
            CapabilityRegistryService registryService,
            GatewayAuthConfigRepository authConfigRepository,
            ObjectMapper objectMapper,
            TokenClassificationService tokenClassificationService) {
        this.agentRegistryService = agentRegistryService;
        this.capabilityFilterService = capabilityFilterService;
        this.auditService = auditService;
        this.registryService = registryService;
        this.authConfigRepository = authConfigRepository;
        this.objectMapper = objectMapper;
        this.tokenClassificationService = tokenClassificationService;
    }

    public String resolveTenant(String sessionId) {
        return sessionToTenant.getOrDefault(sessionId, "unknown");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String httpMethod = httpRequest.getMethod();

        if ("DELETE".equalsIgnoreCase(httpMethod)) {
            handleDelete(httpRequest, httpResponse, chain);
            return;
        }

        String existingSessionId = httpRequest.getHeader("Mcp-Session-Id");
        if (existingSessionId != null && !knownSessionIds.contains(existingSessionId)) {
            log.warn("Rejecting request with stale session ID: {} — gateway was restarted, agent must reconnect",
                    existingSessionId);
            rejectStaleSession(httpRequest, httpResponse, existingSessionId);
            return;
        }

        if (!"POST".equalsIgnoreCase(httpMethod)) {
            chain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(httpRequest);

        JsonNode requestJson = parseRequestJson(wrappedRequest.getCachedBody());
        String requestIdRaw = extractRequestIdRaw(requestJson);
        String requestId = extractRequestId(requestJson);
        String protocolMethod = requestJson != null ? requestJson.path("method").asText("") : "";

        String sessionId = wrappedRequest.getHeader("Mcp-Session-Id");
        if (sessionId != null && blockedSessionIds.contains(sessionId)) {
            String blockedAgentName = resolveAgentName(sessionId);
            auditService.auditAgentConnectionRejected(
                    sessionId,
                    requestId,
                    blockedAgentName,
                    null,
                    protocolMethod,
                    "HTTP",
                    "Blocked session attempted request; reconnect after admin approval.");
            rejectBlocked(httpResponse, requestIdRaw, GatewayErrorCode.AGENT_BLOCKED,
                    "Agent '" + (blockedAgentName != null ? blockedAgentName : "unknown") + "' is blocked by admin. Reconnect after approval.");
            return;
        }

        boolean isExecutionMethod = EXECUTION_METHODS.contains(protocolMethod);
        String reqJwtSubject = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_SUBJECT);

        if (reqJwtSubject != null) {
            String humanStatus = agentRegistryService.getHumanStatus(reqJwtSubject);
            if ("BLOCKED".equals(humanStatus)) {
                String humanUsername = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_PREFERRED_USERNAME);
                String displayUsername = humanUsername != null ? humanUsername : reqJwtSubject;
                log.warn("Request rejected — human '{}' BLOCKED, session={}, method={}",
                        displayUsername, sessionId, protocolMethod);
                auditService.auditHumanConnectionRejected(sessionId, requestId,
                        displayUsername, reqJwtSubject, resolveAgentName(sessionId), protocolMethod,
                        "Human user '" + displayUsername + "' is BLOCKED");
                rejectBlocked(httpResponse, requestIdRaw, GatewayErrorCode.HUMAN_BLOCKED,
                        "Your account '" + displayUsername
                                + "' has been blocked by an administrator."
                                + " Contact your gateway administrator to request access restoration.");
                return;
            }
            if (isExecutionMethod && "PENDING".equals(humanStatus)) {
                String humanUsername = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_PREFERRED_USERNAME);
                String displayUsername = humanUsername != null ? humanUsername : reqJwtSubject;
                log.warn("Execution rejected — human '{}' PENDING approval, session={}, method={}",
                        displayUsername, sessionId, protocolMethod);
                auditService.auditHumanConnectionRejected(sessionId, requestId,
                        displayUsername, reqJwtSubject, resolveAgentName(sessionId), protocolMethod,
                        "Human user '" + displayUsername + "' is PENDING admin approval");
                rejectBlocked(httpResponse, requestIdRaw, GatewayErrorCode.HUMAN_PENDING_APPROVAL,
                        "Your account '" + displayUsername
                                + "' is pending admin approval. An administrator must approve your identity"
                                + " before you can execute operations through this gateway.");
                return;
            }

            String nhiStatus = agentRegistryService.getNhiStatus(reqJwtSubject);
            if ("BLOCKED".equals(nhiStatus)) {
                String nhiClientId = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID);
                String displayName = nhiClientId != null ? nhiClientId : reqJwtSubject;
                log.warn("Request rejected — NHI '{}' BLOCKED, session={}, method={}",
                        displayName, sessionId, protocolMethod);
                auditService.auditNhiConnectionRejected(sessionId, requestId,
                        displayName, nhiClientId, reqJwtSubject, resolveAgentName(sessionId), protocolMethod,
                        "Service identity '" + displayName + "' is BLOCKED");
                rejectBlocked(httpResponse, requestIdRaw, GatewayErrorCode.NHI_BLOCKED,
                        "Service identity '" + displayName
                                + "' has been blocked by an administrator."
                                + " Contact your gateway administrator to request access restoration.");
                return;
            }
            if (isExecutionMethod && "PENDING".equals(nhiStatus)) {
                String nhiClientId = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID);
                String displayName = nhiClientId != null ? nhiClientId : reqJwtSubject;
                log.warn("Execution rejected — NHI '{}' PENDING approval, session={}, method={}",
                        displayName, sessionId, protocolMethod);
                auditService.auditNhiConnectionRejected(sessionId, requestId,
                        displayName, nhiClientId, reqJwtSubject, resolveAgentName(sessionId), protocolMethod,
                        "Service identity '" + displayName + "' is PENDING admin approval");
                rejectBlocked(httpResponse, requestIdRaw, GatewayErrorCode.NHI_PENDING_APPROVAL,
                        "Service identity '" + displayName
                                + "' is pending admin approval. An administrator must approve this identity"
                                + " before it can execute operations through this gateway.");
                return;
            }
        }

        if (sessionId != null) {
            String lifecycleStatus = agentRegistryService.getAgentLifecycleStatusForSession(sessionId);
            if ("DEPROVISIONED".equals(lifecycleStatus)) {
                String deprovAgentName = agentRegistryService.getAgentNameForSession(sessionId);
                log.warn("Request rejected — agent '{}' DEPROVISIONED, session={}, method={}",
                        deprovAgentName, sessionId, protocolMethod);
                auditService.auditAgentConnectionRejected(sessionId, requestId,
                        deprovAgentName, null, protocolMethod, "HTTP",
                        "Agent '" + deprovAgentName + "' has been deprovisioned");
                rejectBlocked(httpResponse, requestIdRaw, GatewayErrorCode.AGENT_DEPROVISIONED,
                        "Agent '" + deprovAgentName
                                + "' has been deprovisioned by an administrator and can no longer operate through this gateway.");
                return;
            }
            String agentStatus = agentRegistryService.getAgentStatusForSession(sessionId);
            if ("BLOCKED".equals(agentStatus)) {
                String blockedAgentName = agentRegistryService.getAgentNameForSession(sessionId);
                log.warn("Request rejected — agent '{}' BLOCKED, session={}, method={}",
                        blockedAgentName, sessionId, protocolMethod);
                auditService.auditAgentConnectionRejected(sessionId, requestId,
                        blockedAgentName, null, protocolMethod, "HTTP",
                        "Agent '" + blockedAgentName + "' is BLOCKED");
                rejectBlocked(httpResponse, requestIdRaw, GatewayErrorCode.AGENT_BLOCKED,
                        "Agent '" + blockedAgentName
                                + "' is blocked by admin. Contact your gateway administrator.");
                return;
            }
            if (isExecutionMethod && "PENDING".equals(agentStatus)) {
                String pendingAgentName = agentRegistryService.getAgentNameForSession(sessionId);
                log.warn("Execution rejected — agent '{}' PENDING approval, session={}, method={}",
                        pendingAgentName, sessionId, protocolMethod);
                auditService.auditAgentConnectionRejected(sessionId, requestId,
                        pendingAgentName, null, protocolMethod, "HTTP",
                        "Agent '" + pendingAgentName + "' is PENDING admin approval");
                rejectBlocked(httpResponse, requestIdRaw, GatewayErrorCode.AGENT_PENDING_APPROVAL,
                        "Agent '" + pendingAgentName
                                + "' is pending admin approval. An administrator must approve this agent"
                                + " before it can execute operations.");
                return;
            }
        }

        if (sessionId != null && !"initialize".equals(protocolMethod)) {
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

        if ("initialize".equals(protocolMethod) && requestJson != null) {
            JsonNode clientInfoNode = requestJson.path("params").path("clientInfo");
            String agentName = clientInfoNode.path("name").asText("unknown");
            String agentVersion = clientInfoNode.path("version").asText(null);
            String preAuthClientId = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID);

            String jwtSubject = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_SUBJECT);
            if (jwtSubject != null && agentRegistryService.isHumanBlockedBySubject(jwtSubject)) {
                String humanUsername = (String) wrappedRequest.getAttribute(GatewayOAuth2Filter.ATTR_PREFERRED_USERNAME);
                String displayUsername = humanUsername != null ? humanUsername : jwtSubject;
                String blockReason = agentRegistryService.getHumanBlockReasonBySubject(jwtSubject);
                log.warn("Pre-initialize rejection — human user BLOCKED: {} (sub={}) reason={}",
                        displayUsername, jwtSubject, blockReason);
                auditService.auditHumanConnectionRejected(null, requestId,
                        displayUsername, jwtSubject,
                        preAuthClientId != null ? preAuthClientId : agentName,
                        "initialize",
                        "Human user '" + displayUsername + "' is BLOCKED: " + blockReason);
                rejectBlocked(httpResponse, requestIdRaw, GatewayErrorCode.HUMAN_BLOCKED,
                        "Your account '" + displayUsername + "' has been blocked by an administrator. Reason: "
                                + blockReason + ". Contact your gateway administrator to request access restoration.");
                return;
            }

            if (jwtSubject != null && agentRegistryService.isNhiBlockedBySubject(jwtSubject)) {
                String nhiClientId = preAuthClientId;
                String blockReason = agentRegistryService.getNhiBlockReasonBySubject(jwtSubject);
                log.warn("Pre-initialize rejection — NHI BLOCKED: sub={}, reason={}", jwtSubject, blockReason);
                auditService.auditNhiConnectionRejected(null, requestId,
                        nhiClientId, nhiClientId, jwtSubject,
                        agentName, "initialize",
                        "Service identity (sub=" + jwtSubject + ") is BLOCKED: " + blockReason);
                rejectBlocked(httpResponse, requestIdRaw, GatewayErrorCode.NHI_BLOCKED,
                        "Service identity '" + (nhiClientId != null ? nhiClientId : jwtSubject)
                                + "' has been blocked by an administrator. Reason: " + blockReason
                                + ". Contact your gateway administrator to request access restoration.");
                return;
            }
        }

        long startTime = System.currentTimeMillis();

        boolean shouldFilterResponse = LIST_METHODS.contains(protocolMethod) && sessionId != null;
        UUID filterAgentId = null;
        if (shouldFilterResponse) {
            filterAgentId = capabilityFilterService.resolveAgentId(sessionId);
            shouldFilterResponse = (filterAgentId != null);
        }

        if (shouldFilterResponse) {
            ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpResponse);

            chain.doFilter(wrappedRequest, responseWrapper);

            long durationMs = System.currentTimeMillis() - startTime;

            filterListResponse(responseWrapper, httpResponse, filterAgentId, protocolMethod);

            afterSdkProcessing(wrappedRequest, httpResponse, durationMs);
        } else {
            chain.doFilter(wrappedRequest, httpResponse);

            long durationMs = System.currentTimeMillis() - startTime;

            afterSdkProcessing(wrappedRequest, httpResponse, durationMs);
        }
    }

    private void afterSdkProcessing(CachedBodyHttpServletRequest wrappedRequest,
            HttpServletResponse httpResponse,
            long durationMs) {
        try {
            byte[] body = wrappedRequest.getCachedBody();
            if (body.length == 0)
                return;

            JsonNode json = objectMapper.readTree(body);
            String protocolMethod = json.path("method").asText("");
            String requestId = json.has("id") ? json.get("id").asText() : null;

            String sessionId = httpResponse.getHeader("Mcp-Session-Id");
            if (sessionId == null) {
                sessionId = wrappedRequest.getHeader("Mcp-Session-Id");
            }
            if (sessionId == null)
                return;

            switch (protocolMethod) {
                case "initialize" -> handleInitialize(json, sessionId, requestId, wrappedRequest);
                case "tools/list" -> handleToolsList(sessionId, requestId, durationMs);
                case "prompts/list" -> handlePromptsList(sessionId, requestId, durationMs);
                case "resources/list" -> handleResourcesList(sessionId, requestId, durationMs);
                case "notifications/initialized" -> {
                }
                default -> {
                    if (requestId == null && !protocolMethod.isEmpty()) {
                        handleNotification(sessionId, protocolMethod, json.path("params"));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error in HTTP MCP audit post-processing: {}", e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleInitialize(JsonNode json, String sessionId, String requestId,
                                  HttpServletRequest httpRequest) {
        knownSessionIds.add(sessionId);

        String wsTenantName = httpRequest.getHeader("X-WS-Tenant");
        if (wsTenantName == null || wsTenantName.isBlank()) {
            String jwtIssuer = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_ISSUER);
            if (jwtIssuer != null) {
                wsTenantName = authConfigRepository.findFirstByIssuerUri(jwtIssuer)
                        .map(config -> config.getWsTenantName())
                        .orElse("default");
                log.info("Tenant resolved from JWT issuer: {} -> {}", jwtIssuer, wsTenantName);
            } else {
                wsTenantName = "default";
            }
        }
        sessionToTenant.put(sessionId, wsTenantName);

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

        String xForwardedFor = httpRequest.getHeader("X-Forwarded-For");
        String clientIp = xForwardedFor != null ? xForwardedFor.split(",")[0].trim() : httpRequest.getRemoteAddr();

        String earlyAgentName = "unknown";
        try {
            earlyAgentName = json.path("params").path("clientInfo").path("name").asText("unknown");
        } catch (Exception ignored) {
        }
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

            if (isProbeAgent(agentName)) {
                log.debug("Skipping probe/test agent registration: {} (session={})", agentName, sessionId);
                registeredSessions.remove(sessionId);
                return;
            }

            String accessToken = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_ACCESS_TOKEN);
            String jwtSignal = (String) httpRequest.getAttribute(GatewayOAuth2Filter.ATTR_CLASSIFICATION_SIGNAL);

            if (accessToken != null && tokenType != null) {
                TokenClassificationService.ClassificationResult introspectionResult =
                        tokenClassificationService.classifyViaIntrospection(accessToken);

                if (introspectionResult != null && !introspectionResult.tokenType().equals(tokenType)) {
                    log.info("Tier 1 introspection overrides Tier 2: {} ({}) -> {} ({})",
                            tokenType, jwtSignal, introspectionResult.tokenType(), introspectionResult.matchedSignal());
                    auditService.auditTokenClassificationOverride(
                            sessionId, resolvedAgentName,
                            tokenType, jwtSignal,
                            introspectionResult.tokenType(), introspectionResult.matchedSignal(),
                            requestId);
                    tokenType = introspectionResult.tokenType();
                }

                TokenClassificationService.ClassificationResult finalResult =
                        introspectionResult != null ? introspectionResult :
                        new TokenClassificationService.ClassificationResult(tokenType, jwtSignal);
                tokenClassificationService.cacheClassification(sessionId, finalResult);
            }

            GatewayAgentEntity agent = agentRegistryService.discoverAgent(
                    agentName, agentVersion, protocolVersion,
                    capabilities.isMissingNode() || capabilities.isEmpty() ? null : capabilities,
                    authClientId, tokenType, wsTenantName);

            UUID humanUserId = null;
            if (GatewayOAuth2Filter.TOKEN_TYPE_HUMAN.equals(tokenType) && jwtSubject != null) {
                GatewayHumanUserEntity humanUser = agentRegistryService.discoverHumanUser(
                        jwtSubject, preferredUsername, userEmail,
                        userFullName, userGivenName, userFamilyName,
                        idpIssuer, emailVerified,
                        realmRoles, clientRoles, customClaims, rawJwtClaims, clientIp,
                        wsTenantName);
                humanUserId = humanUser.getId();
                agentRegistryService.incrementHumanSessionCount(humanUserId);
                log.info("Human-delegated session: user={} linked to agent={}",
                        preferredUsername, authClientId != null ? authClientId : agentName);
            }

            UUID nhiId = null;
            if (GatewayOAuth2Filter.TOKEN_TYPE_AUTOMATED.equals(tokenType) && jwtSubject != null) {
                GatewayNhiEntity nhi = agentRegistryService.discoverNhi(
                        jwtSubject, authClientId, idpIssuer,
                        realmRoles, clientRoles, customClaims, rawJwtClaims, clientIp,
                        wsTenantName);
                if (nhi != null) {
                    nhiId = nhi.getId();
                    agentRegistryService.incrementNhiSessionCount(nhiId);
                    log.info("Automated-agent session: NHI={} (sub={}) linked to agent={}",
                            nhi.getServiceName(), jwtSubject,
                            authClientId != null ? authClientId : agentName);

                    if ("BLOCKED".equals(nhi.getStatus())) {
                        log.warn("NHI is BLOCKED: {} (sub={})", nhi.getServiceName(), jwtSubject);
                        blockedSessionIds.add(sessionId);
                        auditService.auditAgentConnectionRejected(
                                sessionId, requestId,
                                authClientId != null ? authClientId : agentName,
                                agentVersion, "initialize", "HTTP",
                                "Non-Human Identity '" + nhi.getServiceName() + "' is BLOCKED by admin.");
                    }
                }
            }

            agentRegistryService.registerSession(
                    agent.getId(), sessionId,
                    authMethod != null ? authMethod : "HTTP",
                    jwtSubject,
                    tokenType, humanUserId, nhiId, clientIp,
                    wsTenantName);

            auditService.registerSessionIdentity(sessionId,
                    new GatewayAuditService.AuditIdentityContext(
                            tokenType,
                            preferredUsername,
                            humanUserId != null ? humanUserId.toString() : null,
                            authMethod != null ? authMethod : "HTTP",
                            jwtSubject,
                            authClientId,
                            allRoles,
                            nhiId != null ? nhiId.toString() : null,
                            clientIp,
                            wsTenantName));

            if (jwtSubject != null) {
                sessionIdentityCache.put(sessionId, jwtSubject);
            }

            int replaced = agentRegistryService.disconnectExistingSessionsForIdentity(
                    agent.getId(), humanUserId, nhiId, jwtSubject, sessionId);
            if (replaced > 0) {
                log.info("Replaced {} stale session(s) for identity (human={}, nhi={}) + agent {} on reconnect",
                        replaced, humanUserId, nhiId, resolvedAgentName);
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

            auditService.auditServerSessionInitialized(
                    sessionId, protocolVersion,
                    agentName + (agentVersion != null ? " v" + agentVersion : ""),
                    capabilities, requestId, resolvedAgentName);

            log.info("HTTP AGENT SESSION REGISTERED");
            log.info("Session:    {}", sessionId);
            log.info("Agent:      {} v{}", agentName, agentVersion);
            log.info("AuthClient: {}", authClientId != null ? authClientId : "(none — mode=none)");
            log.info("TokenType:  {}", tokenType != null ? tokenType : "(none)");
            log.info("Human:      {}", preferredUsername != null ? preferredUsername : "(automated)");
            log.info("Protocol:   {}", protocolVersion);
            if (replaced > 0) {
                log.info("Replaced:   {} stale session(s)", replaced);
            }

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
            log.warn("Session {} flagged as blocked after initialize: {}", sessionId, blocked.getMessage());
        } catch (Exception e) {
            log.error("Failed to register HTTP agent session {}: {}", sessionId, e.getMessage(), e);
            registeredSessions.remove(sessionId);
            sessionAgentNames.remove(sessionId);
        }
    }

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

    private void filterListResponse(ContentCachingResponseWrapper responseWrapper,
                                      HttpServletResponse originalResponse,
                                      UUID agentId,
                                      String protocolMethod) {
        try {
            byte[] responseBody = responseWrapper.getContentAsByteArray();
            if (responseBody.length == 0) {
                responseWrapper.copyBodyToResponse();
                return;
            }

            String capabilityType;
            String resultArrayField;
            switch (protocolMethod) {
                case "tools/list" -> { capabilityType = "TOOL"; resultArrayField = "tools"; }
                case "prompts/list" -> { capabilityType = "PROMPT"; resultArrayField = "prompts"; }
                case "resources/list", "resources/templates/list" -> {
                    capabilityType = "RESOURCE"; resultArrayField = "resources";
                    if ("resources/templates/list".equals(protocolMethod)) {
                        resultArrayField = "resourceTemplates";
                    }
                }
                default -> {
                    responseWrapper.copyBodyToResponse();
                    return;
                }
            }

            Set<String> allowedNames = capabilityFilterService.getAllowedCapabilities(agentId, capabilityType);

            String bodyStr = new String(responseBody, StandardCharsets.UTF_8);
            String trimmed = bodyStr.trim();
            String jsonPayload;
            String sseIdLine = null;

            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
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

            JsonNode responseJson = objectMapper.readTree(jsonPayload);

            JsonNode resultNode = responseJson.path("result");
            if (resultNode.isMissingNode() || !resultNode.has(resultArrayField)) {
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

            ((ObjectNode) resultNode).set(resultArrayField, filteredArray);

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

            log.debug("Filtered {}: {} -> {} items for agent {}",
                    protocolMethod, itemsArray.size(), filteredArray.size(), agentId);

        } catch (Exception e) {
            log.error("Error filtering {} response for agent {}: {}",
                    protocolMethod, agentId, e.getMessage(), e);
            try {
                responseWrapper.copyBodyToResponse();
            } catch (IOException ioe) {
                log.error("Failed to copy original response body: {}", ioe.getMessage());
            }
        }
    }

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

    private void handleNotification(String sessionId, String method, JsonNode params) {
        String agentName = resolveAgentName(sessionId);
        auditService.auditServerNotificationReceived(sessionId, method, params, agentName);
        log.debug("Audited HTTP notification: session={}, method={}", sessionId, method);
    }

    private void rejectStaleSession(HttpServletRequest request, HttpServletResponse response, String existingSessionId)
            throws IOException {
        String errorMessage = "Session expired. Gateway was restarted. Please reconnect the AI agent.";
        String requestId = null;
        JsonNode requestJson = null;
        try {
            byte[] body = request.getInputStream().readAllBytes();
            if (body.length > 0) {
                requestJson = objectMapper.readTree(body);
                if (requestJson.has("id")) {
                    requestId = requestJson.get("id").toString();
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse request body for stale session rejection: {}", e.getMessage());
        }

        String agentName = agentRegistryService.getAgentNameBySessionId(existingSessionId);
        auditService.auditServerRequestRejected(existingSessionId, agentName, requestJson, errorMessage);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"" + errorMessage + "\"},\"id\":"
                        + (requestId != null ? requestId : "null") + "}");
    }

    private void rejectBlocked(HttpServletResponse response, String requestIdRaw,
                               GatewayErrorCode errorCode, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + errorCode.getCode()
                        + ",\"message\":\"" + escapeJson(message) + "\"},\"id\":"
                        + requestIdRaw + "}");
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public void addBlockedSessionId(String sessionId) {
        if (sessionId != null) {
            blockedSessionIds.add(sessionId);
        }
    }

    @org.springframework.context.event.EventListener
    public void onBlockedSessionEvent(BlockedSessionEvent event) {
        addBlockedSessionId(event.sessionId());
        log.info("Session flagged for blocked {} '{}': {}",
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
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private void handleDelete(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        String sessionId = request.getHeader("Mcp-Session-Id");

        chain.doFilter(request, response);

        if (sessionId != null && registeredSessions.containsKey(sessionId)) {
            try {
                String agentName = resolveAgentName(sessionId);

                auditService.auditServerSessionDisconnectedSync(sessionId, agentName);

                agentRegistryService.disconnectSession(sessionId);

                auditService.evictSessionIdentity(sessionId);

                registeredSessions.remove(sessionId);
                knownSessionIds.remove(sessionId);
                blockedSessionIds.remove(sessionId);
                sessionAgentNames.remove(sessionId);
                sessionIdentityCache.remove(sessionId);
                sessionToTenant.remove(sessionId);
                tokenClassificationService.evictSession(sessionId);

                log.info("HTTP session disconnected: {} (agent={})", sessionId, agentName);
            } catch (Exception e) {
                log.error("Failed to audit HTTP session disconnect {}: {}", sessionId, e.getMessage());
            }
        }
    }

    private String resolveAgentName(String sessionId) {
        if (sessionId == null) return "unknown";
        String name = sessionAgentNames.get(sessionId);
        if (name != null) {
            return name;
        }

        String dbName = agentRegistryService.getAgentNameBySessionId(sessionId);
        if (dbName != null && !"unknown".equals(dbName)) {
            sessionAgentNames.putIfAbsent(sessionId, dbName);
            return dbName;
        }

        return "unresolved";
    }
}
