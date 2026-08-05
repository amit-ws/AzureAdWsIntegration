package com.ws.wsAgenticSecurityGateway.protocol.a2a.inbound;

import com.ws.wsAgenticSecurityGateway.orchestration.model.ClientInfo;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestAttributeKeys;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestAttributes;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestContext;
import com.ws.wsAgenticSecurityGateway.security.GatewayOAuth2Filter;
import com.ws.wsAgenticSecurityGateway.security.workload.WorkloadIdentity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.a2aproject.sdk.spec.Message;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps an inbound A2A request to a protocol-neutral {@link RequestContext} — the A2A analogue of
 * {@code McpRequestContextFactory}. The caller identity comes from the {@code jwt.*} servlet-request
 * attributes stamped by {@link GatewayOAuth2Filter} after it validated the inbound token; the A2A
 * {@code contextId} is the session umbrella and the {@code messageId} the per-request wire id.
 *
 * <p>This is the single place the A2A request handle is read — everything downstream (the spine and the PDP)
 * reads the neutral {@code RequestContext} by {@link RequestAttributeKeys}, so no A2A SDK type leaks past here.
 */
@Slf4j
final class A2aRequestContextFactory {

    private A2aRequestContextFactory() {
    }

    static RequestContext from(HttpServletRequest request, Message message, WorkloadIdentity identity) {
        if (request == null) {
            return RequestContext.empty();
        }

        // The calling agent's identity comes through the WorkloadIdentitySource seam — JWT today, SPIFFE later.
        WorkloadIdentity id = identity != null ? identity : WorkloadIdentity.ANONYMOUS;
        ClientInfo clientInfo = id.agentId() != null ? new ClientInfo(id.agentId(), null) : null;

        String sessionId = message != null ? message.contextId() : null;

        Map<String, Object> attrs = new HashMap<>();
        put(attrs, RequestAttributeKeys.AGENT_CLIENT_ID, id.agentId());
        put(attrs, RequestAttributeKeys.JWT_SUBJECT, id.subject());
        put(attrs, RequestAttributeKeys.USER_IDENTITY, request.getAttribute(GatewayOAuth2Filter.ATTR_PREFERRED_USERNAME));
        put(attrs, RequestAttributeKeys.IDP_ISSUER, request.getAttribute(GatewayOAuth2Filter.ATTR_ISSUER));
        put(attrs, RequestAttributeKeys.TOKEN_TYPE, request.getAttribute(GatewayOAuth2Filter.ATTR_TOKEN_TYPE));
        put(attrs, RequestAttributeKeys.AGENT_ROLES, request.getAttribute(GatewayOAuth2Filter.ATTR_ALL_ROLES));
        put(attrs, RequestAttributeKeys.REALM_ROLES, request.getAttribute(GatewayOAuth2Filter.ATTR_REALM_ROLES));
        put(attrs, RequestAttributeKeys.CLIENT_ROLES, request.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ROLES));
        put(attrs, RequestAttributeKeys.CLIENT_IP, request.getRemoteAddr());
        if (message != null) {
            put(attrs, RequestAttributeKeys.REQUEST_ID, message.messageId());
        }

        // Multi-hop: carry the inbound token's raw claims (its act_chain / act) so the spine's ActChainBuilder
        // extends the delegation lineage, and continue the trace from the inbound OBO's trace_id (else a header).
        Object rawClaims = request.getAttribute(GatewayOAuth2Filter.ATTR_RAW_CLAIMS);
        put(attrs, RequestAttributeKeys.RAW_JWT_CLAIMS, rawClaims);
        String trace = claimStr(rawClaims, "trace_id");
        if (trace == null) {
            trace = str(request.getHeader("X-Trace-Id"));
        }
        put(attrs, RequestAttributeKeys.TRACE_ID, trace);

        RequestAttributes attributes = attrs::get;
        return new RequestContext(clientInfo, sessionId, attributes);
    }

    private static void put(Map<String, Object> attrs, String key, Object value) {
        if (value != null) {
            attrs.put(key, value);
        }
    }

    private static String str(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String claimStr(Object rawClaims, String key) {
        if (rawClaims instanceof Map<?, ?> claims) {
            Object v = claims.get(key);
            return v != null && !String.valueOf(v).isBlank() ? String.valueOf(v) : null;
        }
        return null;
    }
}
