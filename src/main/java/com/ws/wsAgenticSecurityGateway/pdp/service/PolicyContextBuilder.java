package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.orchestration.model.ClientInfo;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestAttributeKeys;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestContext;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.session.ClientSession;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.session.SessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class PolicyContextBuilder {

    private final AgentRegistryService agentRegistryService;

    private volatile SessionManager sessionManager;

    @FunctionalInterface
    public interface CustomAttributeProvider {
        Map<String, Object> getAttributes(String agentName, String action,
                                           String resourceName, String serverName,
                                           Map<String, Object> arguments);
    }

    private final CustomAttributeService customAttributeService;
    private final List<CustomAttributeProvider> attributeProviders;

    public PolicyContextBuilder(AgentRegistryService agentRegistryService,
                                 CustomAttributeService customAttributeService,
                                 List<CustomAttributeProvider> attributeProviders) {
        this.agentRegistryService = agentRegistryService;
        this.customAttributeService = customAttributeService;
        this.attributeProviders = attributeProviders != null
                ? attributeProviders : Collections.emptyList();
        if (!this.attributeProviders.isEmpty()) {
            log.info("Registered {} custom attribute provider(s) for policy evaluation",
                    this.attributeProviders.size());
        }
    }

    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public PolicyEvaluationRequest buildForToolCall(
            RequestContext requestContext,
            String publicName,
            String serverName,
            String originalName,
            Map<String, Object> arguments,
            String correlationId,
            String sessionId) {

        return buildRequest(requestContext, "toolCall", publicName, serverName,
                originalName, "TOOL", arguments, correlationId, sessionId);
    }

    public PolicyEvaluationRequest buildForSkillInvocation(
            RequestContext requestContext,
            String publicName,
            String serverName,
            String originalName,
            Map<String, Object> arguments,
            String correlationId,
            String sessionId) {

        return buildRequest(requestContext, "skillInvocation", publicName, serverName,
                originalName, "SKILL", arguments, correlationId, sessionId);
    }

    public PolicyEvaluationRequest buildForPromptGet(
            RequestContext requestContext,
            String publicName,
            String serverName,
            String originalName,
            String correlationId,
            String sessionId) {

        return buildRequest(requestContext, "promptGet", publicName, serverName,
                originalName, "PROMPT", null, correlationId, sessionId);
    }

    public PolicyEvaluationRequest buildForResourceRead(
            RequestContext requestContext,
            String publicName,
            String serverName,
            String originalName,
            String correlationId,
            String sessionId) {

        return buildRequest(requestContext, "resourceRead", publicName, serverName,
                originalName, "RESOURCE", null, correlationId, sessionId);
    }

    private PolicyEvaluationRequest buildRequest(
            RequestContext requestContext,
            String action,
            String publicName,
            String serverName,
            String originalName,
            String resourceType,
            Map<String, Object> arguments,
            String correlationId,
            String sessionId) {

        String agentName = "unknown";
        String agentVersion = null;
        try {
            ClientInfo ci = requestContext != null ? requestContext.clientInfo() : null;
            if (ci != null) {
                agentName = ci.name() != null ? ci.name() : "unknown";
                agentVersion = ci.version();
            }
        } catch (Exception e) {
            log.debug("Could not extract agent info from request context: {}", e.getMessage());
        }

        // Stateless bridge (Delta 2): the synthetic request carries no clientInfo — the authoritative caller
        // identity rides on the request attributes as the JWT client_id. Use it as the policy principal so a
        // stateless request matches the SAME agent policies as its session-mode counterpart.
        if ("unknown".equals(agentName) && requestContext != null) {
            try {
                Object tcClientId = requestContext.attributes().get(RequestAttributeKeys.AGENT_CLIENT_ID);
                if (tcClientId instanceof String s && !s.isBlank()) {
                    agentName = s;
                }
            } catch (Exception e) {
                log.debug("Could not resolve stateless agent client id: {}", e.getMessage());
            }
        }

        String approvalStatus = "UNKNOWN";
        try {
            List<GatewayAgentEntity> agents = agentRegistryService.findAgentsByName(agentName);
            if (agents != null && !agents.isEmpty()) {
                approvalStatus = agents.get(0).getApprovalStatus();
            }
        } catch (Exception e) {
            log.debug("Could not fetch agent approval status: {}", e.getMessage());
        }

        Map<String, Object> transportContext = new HashMap<>();
        Map<String, String> httpHeaders = Collections.emptyMap();
        String sourceIp = null;
        try {
            if (requestContext != null) {
                Object ip = requestContext.attributes().get(RequestAttributeKeys.CLIENT_IP);
                if (ip != null) {
                    sourceIp = String.valueOf(ip);
                    transportContext.put("clientIp", sourceIp);
                }
                Object tcAgentName = requestContext.attributes().get(RequestAttributeKeys.AGENT_NAME);
                if (tcAgentName != null) transportContext.put("agentName", tcAgentName);
                Object tcCorrelation = requestContext.attributes().get(RequestAttributeKeys.CORRELATION_ID);
                if (tcCorrelation != null) transportContext.put("correlationId", tcCorrelation);

                Object headersObj = requestContext.attributes().get(RequestAttributeKeys.HTTP_HEADERS);
                if (headersObj instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> castHeaders = (Map<String, String>) rawMap;
                    httpHeaders = castHeaders;
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract transport context: {}", e.getMessage());
        }

        Map<String, Object> customAttrs = resolveAllCustomAttributes(
                httpHeaders, transportContext, agentName, action,
                publicName, serverName, arguments);

        String agentClientId = null;
        String jwtSubject = null;
        List<String> agentRoles = null;
        List<String> tcRealmRoles = null;
        List<String> tcClientRoles = null;
        String userIdentity = null;
        String tokenType = null;
        Map<String, Object> jwtCustomClaims = null;
        try {
            if (requestContext != null) {
                Object o;
                o = requestContext.attributes().get(RequestAttributeKeys.AGENT_CLIENT_ID);
                if (o instanceof String s) agentClientId = s;
                o = requestContext.attributes().get(RequestAttributeKeys.JWT_SUBJECT);
                if (o instanceof String s) jwtSubject = s;
                o = requestContext.attributes().get(RequestAttributeKeys.USER_IDENTITY);
                if (o instanceof String s) userIdentity = s;
                o = requestContext.attributes().get(RequestAttributeKeys.TOKEN_TYPE);
                if (o instanceof String s) tokenType = s;
                o = requestContext.attributes().get(RequestAttributeKeys.AGENT_ROLES);
                if (o instanceof List<?> l) agentRoles = l.stream().map(String::valueOf).toList();
                o = requestContext.attributes().get(RequestAttributeKeys.REALM_ROLES);
                if (o instanceof List<?> l) tcRealmRoles = l.stream().map(String::valueOf).toList();
                o = requestContext.attributes().get(RequestAttributeKeys.CLIENT_ROLES);
                if (o instanceof List<?> l) tcClientRoles = l.stream().map(String::valueOf).toList();
                o = requestContext.attributes().get(RequestAttributeKeys.CUSTOM_CLAIMS);
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    jwtCustomClaims = cast;
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract JWT claims from transport context: {}", e.getMessage());
        }

        return PolicyEvaluationRequest.builder()
                .agentName(agentName)
                .agentVersion(agentVersion)
                .agentApprovalStatus(approvalStatus)
                .agentSessionId(sessionId)
                .agentClientId(agentClientId)
                .jwtSubject(jwtSubject)
                .agentRoles(agentRoles)
                .realmRoles(tcRealmRoles)
                .clientRoles(tcClientRoles)
                .userIdentity(userIdentity)
                .tokenType(tokenType)
                .jwtCustomClaims(jwtCustomClaims)
                .action(action)
                .resourceName(publicName)
                .serverName(serverName)
                .originalName(originalName)
                .resourceType(resourceType)
                .arguments(arguments != null ? sanitizeArguments(arguments) : null)
                .correlationId(correlationId)
                .sourceIp(sourceIp)
                .customAttributes(customAttrs.isEmpty() ? null : customAttrs)
                .build();
    }

    private Map<String, Object> resolveAllCustomAttributes(
            Map<String, String> httpHeaders,
            Map<String, Object> transportContext,
            String agentName, String action,
            String resourceName, String serverName,
            Map<String, Object> arguments) {

        Map<String, Object> merged = new HashMap<>();

        try {
            Map<String, Object> registeredAttrs = customAttributeService.resolveAttributes(
                    httpHeaders, transportContext, agentName);
            if (!registeredAttrs.isEmpty()) {
                merged.putAll(registeredAttrs);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve DB-registered custom attributes: {}", e.getMessage());
        }

        Map<String, Object> providerAttrs = collectCustomAttributes(
                agentName, action, resourceName, serverName, arguments);
        if (!providerAttrs.isEmpty()) {
            merged.putAll(providerAttrs);
        }

        return merged;
    }

    private Map<String, Object> collectCustomAttributes(
            String agentName, String action, String resourceName,
            String serverName, Map<String, Object> arguments) {
        if (attributeProviders.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> merged = new HashMap<>();
        for (CustomAttributeProvider provider : attributeProviders) {
            try {
                Map<String, Object> attrs = provider.getAttributes(
                        agentName, action, resourceName, serverName, arguments);
                if (attrs != null && !attrs.isEmpty()) {
                    merged.putAll(attrs);
                }
            } catch (Exception e) {
                log.warn("Custom attribute provider {} failed: {}",
                        provider.getClass().getSimpleName(), e.getMessage());
            }
        }
        return merged;
    }

    private Map<String, Object> sanitizeArguments(Map<String, Object> args) {
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.length() > 2000) {
                sanitized.put(entry.getKey(), s.substring(0, 2000) + "...[truncated]");
            } else if (value instanceof byte[]) {
                sanitized.put(entry.getKey(), "[binary data]");
            } else {
                sanitized.put(entry.getKey(), value);
            }
        }
        return sanitized;
    }
}
