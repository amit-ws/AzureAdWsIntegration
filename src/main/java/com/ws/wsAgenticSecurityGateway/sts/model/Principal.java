package com.ws.wsAgenticSecurityGateway.sts.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One element of an {@link ActChain} delegation lineage.
 *
 * <p>{@code verified} is true only when the principal is strongly authenticated (a human resolved to
 * a registry record, or an agent resolved to its registry UUID + a JWT client_id). A weak/inferred
 * root is emitted with {@code verified=false} — never fabricated (Locked Decision 3).
 */
public record Principal(String id, PrincipalType type, boolean verified,
                        String idp, String username, String clientId) {

    public enum PrincipalType { HUMAN, NHI, AGENT }

    public static Principal human(String id, String username, String idp, boolean verified) {
        return new Principal(id, PrincipalType.HUMAN, verified, idp, username, null);
    }

    public static Principal nhi(String id, boolean verified) {
        return new Principal(id, PrincipalType.NHI, verified, null, null, null);
    }

    public static Principal agent(String id, String clientId, boolean verified) {
        return new Principal(id, PrincipalType.AGENT, verified, null, null, clientId);
    }

    public static Principal unknownRoot(String idp) {
        return new Principal("unknown", PrincipalType.HUMAN, false, idp, null, null);
    }

    /** The token-claim form of this principal: an ordered map with null optionals omitted. */
    public Map<String, Object> toClaim() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type.name().toLowerCase(Locale.ROOT));
        m.put("verified", verified);
        if (idp != null) m.put("idp", idp);
        if (username != null) m.put("username", username);
        if (clientId != null) m.put("clientId", clientId);
        return m;
    }

    /** Rebuild a principal from its {@link #toClaim()} form (reading an inbound token's {@code act_chain}). */
    public static Principal fromClaim(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        String id = strOrNull(m.get("id"));
        if (id == null) {
            return null;
        }
        return new Principal(id, parseType(m.get("type")), Boolean.TRUE.equals(m.get("verified")),
                strOrNull(m.get("idp")), strOrNull(m.get("username")), strOrNull(m.get("clientId")));
    }

    private static PrincipalType parseType(Object type) {
        if (type != null) {
            try {
                return PrincipalType.valueOf(String.valueOf(type).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to the default
            }
        }
        return PrincipalType.AGENT;
    }

    private static String strOrNull(Object o) {
        return o != null && !String.valueOf(o).isBlank() ? String.valueOf(o) : null;
    }
}
