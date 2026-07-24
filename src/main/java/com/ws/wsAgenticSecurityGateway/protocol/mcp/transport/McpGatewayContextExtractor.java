package com.ws.wsAgenticSecurityGateway.protocol.mcp.transport;
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
        ctx.put("traceId", traceId);

        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            ctx.put("authorization", auth);
        }

        String agentName = request.getHeader("X-Agent-Name");
        if (agentName != null && !agentName.isBlank()) {
            ctx.put("agentName", agentName);
        }

        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank()) {
            ctx.put("correlationId", correlationId);
        }

        String clientIp = request.getRemoteAddr();
        if (clientIp != null) {
            ctx.put("clientIp", clientIp);
        }

        propagateJwtClaims(request, ctx);

        Map<String, String> httpHeaders = captureHttpHeaders(request);
        if (!httpHeaders.isEmpty()) {
            ctx.put("_httpHeaders", Collections.unmodifiableMap(httpHeaders));
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

        ctx.put("agentClientId", clientId);
        putIfPresent(ctx, "jwtSubject", request.getAttribute(GatewayOAuth2Filter.ATTR_SUBJECT));
        putIfPresent(ctx, "userIdentity", request.getAttribute(GatewayOAuth2Filter.ATTR_PREFERRED_USERNAME));
        putIfPresent(ctx, "userEmail", request.getAttribute(GatewayOAuth2Filter.ATTR_EMAIL));
        putIfPresent(ctx, "userFullName", request.getAttribute(GatewayOAuth2Filter.ATTR_FULL_NAME));
        putIfPresent(ctx, "userGivenName", request.getAttribute(GatewayOAuth2Filter.ATTR_GIVEN_NAME));
        putIfPresent(ctx, "userFamilyName", request.getAttribute(GatewayOAuth2Filter.ATTR_FAMILY_NAME));
        putIfPresent(ctx, "userEmailVerified", request.getAttribute(GatewayOAuth2Filter.ATTR_EMAIL_VERIFIED));
        putIfPresent(ctx, "idpIssuer", request.getAttribute(GatewayOAuth2Filter.ATTR_ISSUER));
        putIfPresent(ctx, "tokenType", request.getAttribute(GatewayOAuth2Filter.ATTR_TOKEN_TYPE));
        putIfPresent(ctx, "classificationSignal", request.getAttribute(GatewayOAuth2Filter.ATTR_CLASSIFICATION_SIGNAL));
        putIfPresent(ctx, "authMethod", request.getAttribute(GatewayOAuth2Filter.ATTR_AUTH_METHOD));
        putIfPresent(ctx, "agentRoles", request.getAttribute(GatewayOAuth2Filter.ATTR_ALL_ROLES));
        putIfPresent(ctx, "realmRoles", request.getAttribute(GatewayOAuth2Filter.ATTR_REALM_ROLES));
        putIfPresent(ctx, "clientRoles", request.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ROLES));
        putIfPresent(ctx, "customClaims", request.getAttribute(GatewayOAuth2Filter.ATTR_CUSTOM_CLAIMS));
        putIfPresent(ctx, "rawJwtClaims", request.getAttribute(GatewayOAuth2Filter.ATTR_RAW_CLAIMS));
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
