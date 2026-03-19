package com.ws.wsAgenticSecurityGateway.wsServer.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Extracts authentication and agent identity context from HTTP request headers
 * into {@link McpTransportContext} for the MCP SDK's Reactor pipeline.
 *
 * <p>The extracted context is available in tool/prompt/resource handlers via
 * {@code exchange.transportContext().get("key")}, enabling:
 * <ul>
 *   <li>Agent token forwarding to enterprise MCP servers</li>
 *   <li>Agent identification for audit logging</li>
 *   <li>Correlation ID propagation across the request chain</li>
 * </ul>
 *
 * <p>This extractor is used by {@code HttpServletStreamableServerTransportProvider}
 * in HTTP mode. In stdio mode, context is injected differently (via JSON-RPC id
 * in {@link ServerTransportProvider}).
 *
 * <h3>HTTP Headers Capture (for custom attribute resolution)</h3>
 * <p>All HTTP headers are captured in a nested map under the key {@code _httpHeaders}.
 * This enables the Custom Attribute system to resolve HEADER-sourced attributes
 * from any header the agent sends — without requiring code changes here.
 * Sensitive headers (Authorization, Cookie) are excluded from the headers map.
 */
@Component
@Slf4j
public class McpGatewayContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

    /** Headers excluded from the _httpHeaders capture (sensitive data). */
    private static final Set<String> EXCLUDED_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie",
            "proxy-authorization", "www-authenticate"
    );

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        Map<String, Object> ctx = new HashMap<>();

        // ── Auth token (for agent token forwarding to enterprise MCP servers) ────
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            ctx.put("authorization", auth);
        }

        // ── Agent identity headers ──────────────────────────────────────────
        String agentName = request.getHeader("X-Agent-Name");
        if (agentName != null && !agentName.isBlank()) {
            ctx.put("agentName", agentName);
        }

        // ── Correlation ID (if client sends one for distributed tracing) ────
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank()) {
            ctx.put("correlationId", correlationId);
        }

        // ── Client IP for audit ─────────────────────────────────────────────
        String clientIp = request.getRemoteAddr();
        if (clientIp != null) {
            ctx.put("clientIp", clientIp);
        }

        // ── JWT claims (set by GatewayOAuth2Filter as request attributes) ─────
        propagateJwtClaims(request, ctx);

        // ── Capture ALL HTTP headers for custom attribute resolution ────────
        // Stored as a sub-map so CustomAttributeService can resolve HEADER-sourced
        // attributes from ANY header the agent sends (scalable for cloud deployment).
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

    /**
     * Propagates JWT claims from request attributes (set by GatewayOAuth2Filter)
     * into the MCP transport context. These flow through the SDK's Reactor pipeline
     * and are available in tool/prompt/resource handlers via exchange.transportContext().
     */
    @SuppressWarnings("unchecked")
    private void propagateJwtClaims(HttpServletRequest request, Map<String, Object> ctx) {
        Object clientId = request.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID);
        if (clientId == null) return; // No JWT claims — mode=none or non-OAuth2 request

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

    /**
     * Capture all HTTP headers from the request, excluding sensitive ones.
     * Headers are stored with their original casing for case-insensitive lookup
     * in the Custom Attribute resolution layer.
     */
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
