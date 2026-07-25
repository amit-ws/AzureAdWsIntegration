package com.ws.wsAgenticSecurityGateway.orchestration.model;

/**
 * The contract of {@link RequestContext#attributes()} keys the governance spine and PDP consume — the
 * single authoritative list an inbound protocol boundary must satisfy.
 *
 * <p>Before this holder existed the keys were scattered string literals across {@code HopOrchestrator} and
 * {@code PolicyContextBuilder}; an inbound boundary author had no way to know which keys the spine reads, so
 * omitting one (e.g. {@link #AGENT_CLIENT_ID}) silently broke governance rather than failing at compile time.
 * Each protocol's inbound boundary (MCP: {@code McpGatewayContextExtractor} + {@code McpRequestContextFactory};
 * A2A later: its own) populates these keys; the spine reads them by these same constants. Populating a key is
 * optional — the spine tolerates a {@code null} for any of them — but the <em>names</em> are the shared vocabulary.
 *
 * <p>Values are the historical wire strings and must not change: other readers (the audit layer, the stateless
 * synthetic context) still reference some of these by literal, so the constant name is neutral while the value
 * stays compatible.
 */
public final class RequestAttributeKeys {

    private RequestAttributeKeys() {
    }

    /** Request-scoped umbrella trace id (spans every leg of a request). */
    public static final String TRACE_ID = "traceId";

    /** The inbound request's wire id (opaque; used for log/audit correlation). */
    public static final String REQUEST_ID = "jsonRpcRequestId";

    /** The caller's authenticated client id (JWT {@code client_id}); the stateless-mode policy principal. */
    public static final String AGENT_CLIENT_ID = "agentClientId";

    /** JWT subject claim. */
    public static final String JWT_SUBJECT = "jwtSubject";

    /** Human/user identity behind the agent (preferred username). */
    public static final String USER_IDENTITY = "userIdentity";

    /** Identity-provider issuer. */
    public static final String IDP_ISSUER = "idpIssuer";

    /** Token type / classification. */
    public static final String TOKEN_TYPE = "tokenType";

    /** The full raw JWT claim set. */
    public static final String RAW_JWT_CLAIMS = "rawJwtClaims";

    /** Source ip of the inbound request. */
    public static final String CLIENT_IP = "clientIp";

    /** Caller-supplied agent name header. */
    public static final String AGENT_NAME = "agentName";

    /** Caller-supplied correlation id header. */
    public static final String CORRELATION_ID = "correlationId";

    /** Captured inbound HTTP headers (sensitive headers excluded), for header-based custom attributes. */
    public static final String HTTP_HEADERS = "_httpHeaders";

    /** All roles granted to the caller (realm + client, flattened). */
    public static final String AGENT_ROLES = "agentRoles";

    /** Realm-scoped roles. */
    public static final String REALM_ROLES = "realmRoles";

    /** Client-scoped roles. */
    public static final String CLIENT_ROLES = "clientRoles";

    /** Custom JWT claims surfaced for policy evaluation. */
    public static final String CUSTOM_CLAIMS = "customClaims";
}
