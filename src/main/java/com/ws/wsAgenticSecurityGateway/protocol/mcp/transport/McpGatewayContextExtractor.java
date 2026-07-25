package com.ws.wsAgenticSecurityGateway.protocol.mcp.transport;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestAttributeKeys;
import com.ws.wsAgenticSecurityGateway.security.GatewayOAuth2Filter;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class McpGatewayContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

    private static final Set<String> EXCLUDED_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie",
            "proxy-authorization", "www-authenticate"
    );

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        Map<String, Object> ctx = new HashMap<>();

        // Request-scoped trace id (umbrella over every leg): honor an inbound X-Trace-Id for cross-service
        // continuity, otherwise mint one here — the earliest per-request point that reaches the orchestrator.
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        ctx.put(RequestAttributeKeys.TRACE_ID, traceId);

        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            ctx.put("authorization", auth);
        }

        String agentName = request.getHeader("X-Agent-Name");
        if (agentName != null && !agentName.isBlank()) {
            ctx.put(RequestAttributeKeys.AGENT_NAME, agentName);
        }

        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank()) {
            ctx.put(RequestAttributeKeys.CORRELATION_ID, correlationId);
        }

        String clientIp = request.getRemoteAddr();
        if (clientIp != null) {
            ctx.put(RequestAttributeKeys.CLIENT_IP, clientIp);
        }

        propagateJwtClaims(request, ctx);

        Map<String, String> httpHeaders = captureHttpHeaders(request);
        if (!httpHeaders.isEmpty()) {
            ctx.put(RequestAttributeKeys.HTTP_HEADERS, Collections.unmodifiableMap(httpHeaders));
        }

        if (!ctx.isEmpty()) {
            log.debug("Extracted MCP transport context: keys={}, headerCount={}",
                    ctx.keySet(), httpHeaders.size());
        }

        return ctx.isEmpty() ? McpTransportContext.EMPTY : McpTransportContext.create(ctx);
    }

    @SuppressWarnings("unchecked")
    private void propagateJwtClaims(HttpServletRequest request, Map<String, Object> ctx) {
        Object clientId = request.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID);
        if (clientId == null) return;

        ctx.put(RequestAttributeKeys.AGENT_CLIENT_ID, clientId);
        putIfPresent(ctx, RequestAttributeKeys.JWT_SUBJECT, request.getAttribute(GatewayOAuth2Filter.ATTR_SUBJECT));
        putIfPresent(ctx, RequestAttributeKeys.USER_IDENTITY, request.getAttribute(GatewayOAuth2Filter.ATTR_PREFERRED_USERNAME));
        putIfPresent(ctx, "userEmail", request.getAttribute(GatewayOAuth2Filter.ATTR_EMAIL));
        putIfPresent(ctx, "userFullName", request.getAttribute(GatewayOAuth2Filter.ATTR_FULL_NAME));
        putIfPresent(ctx, "userGivenName", request.getAttribute(GatewayOAuth2Filter.ATTR_GIVEN_NAME));
        putIfPresent(ctx, "userFamilyName", request.getAttribute(GatewayOAuth2Filter.ATTR_FAMILY_NAME));
        putIfPresent(ctx, "userEmailVerified", request.getAttribute(GatewayOAuth2Filter.ATTR_EMAIL_VERIFIED));
        putIfPresent(ctx, RequestAttributeKeys.IDP_ISSUER, request.getAttribute(GatewayOAuth2Filter.ATTR_ISSUER));
        putIfPresent(ctx, RequestAttributeKeys.TOKEN_TYPE, request.getAttribute(GatewayOAuth2Filter.ATTR_TOKEN_TYPE));
        putIfPresent(ctx, "classificationSignal", request.getAttribute(GatewayOAuth2Filter.ATTR_CLASSIFICATION_SIGNAL));
        putIfPresent(ctx, "authMethod", request.getAttribute(GatewayOAuth2Filter.ATTR_AUTH_METHOD));
        putIfPresent(ctx, RequestAttributeKeys.AGENT_ROLES, request.getAttribute(GatewayOAuth2Filter.ATTR_ALL_ROLES));
        putIfPresent(ctx, RequestAttributeKeys.REALM_ROLES, request.getAttribute(GatewayOAuth2Filter.ATTR_REALM_ROLES));
        putIfPresent(ctx, RequestAttributeKeys.CLIENT_ROLES, request.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ROLES));
        putIfPresent(ctx, RequestAttributeKeys.CUSTOM_CLAIMS, request.getAttribute(GatewayOAuth2Filter.ATTR_CUSTOM_CLAIMS));
        putIfPresent(ctx, RequestAttributeKeys.RAW_JWT_CLAIMS, request.getAttribute(GatewayOAuth2Filter.ATTR_RAW_CLAIMS));
    }

    private void putIfPresent(Map<String, Object> ctx, String key, Object value) {
        if (value != null) {
            ctx.put(key, value);
        }
    }

    private Map<String, String> captureHttpHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) return headers;

        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (name != null && !EXCLUDED_HEADERS.contains(name.toLowerCase())) {
                String value = request.getHeader(name);
                if (value != null && !value.isBlank()) {
                    headers.put(name, value);
                }
            }
        }
        return headers;
    }
}
