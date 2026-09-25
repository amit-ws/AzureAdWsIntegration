package com.ws.wsAgenticSecurityGateway.sts.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One element of an {@link ActChain} delegation lineage.
 *
 * <p>{@code verified} is true only when the principal is strongly authenticated (a human resolved to
 * a registry record, or an agent resolved to its registry UUID + a validated workload credential). A
 * weak/inferred root is emitted with {@code verified=false} — never fabricated (Locked Decision 3).
 *
 * <p>For an AGENT, {@code workloadId} is the source-specific verified identifier (the Keycloak
 * {@code client_id} today; a SPIFFE ID once SPIFFE is the source) and {@code identitySource} says which
 * ({@code "KEYCLOAK"} / {@code "SPIFFE"}). One id slot, source-tagged — the same shape the registry
 * ({@code identity_source} + {@code workload_id}) and the {@code WorkloadIdentity} seam use — so a future
 * SPIFFE swap is a value change, not a new claim.
 */
public record Principal(String id, PrincipalType type, boolean verified,
                        String idp, String username, String workloadId, String identitySource) {

    public enum PrincipalType { HUMAN, NHI, AGENT }

    public static Principal human(String id, String username, String idp, boolean verified) {
        return new Principal(id, PrincipalType.HUMAN, verified, idp, username, null, null);
    }

    public static Principal nhi(String id, boolean verified) {
        return new Principal(id, PrincipalType.NHI, verified, null, null, null, null);
    }

    public static Principal agent(String id, String workloadId, String identitySource, boolean verified) {
        return new Principal(id, PrincipalType.AGENT, verified, null, null, workloadId, identitySource);
    }

    public static Principal unknownRoot(String idp) {
        return new Principal("unknown", PrincipalType.HUMAN, false, idp, null, null, null);
    }

    /** The token-claim form of this principal: an ordered map with null optionals omitted. */
    public Map<String, Object> toClaim() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type.name().toLowerCase(Locale.ROOT));
        m.put("verified", verified);
        if (idp != null) m.put("idp", idp);
        if (username != null) m.put("username", username);
        if (workloadId != null) {
            m.put("workload_id", workloadId);
            // Back-compat: existing act_chain consumers (e.g. the console's governance trail) read the actor's
            // "clientId" for its display name. Keep emitting it (== workload_id for a Keycloak client_id) so the
            // move to workload_id/identity_source never falls back to the raw UUID id for session-bound actors.
            m.put("clientId", workloadId);
        }
        if (identitySource != null) m.put("identity_source", identitySource);
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
        // Prefer workload_id; fall back to the legacy "clientId" key so an in-flight older token still resolves.
        String workloadId = strOrNull(m.get("workload_id"));
        if (workloadId == null) {
            workloadId = strOrNull(m.get("clientId"));
        }
        return new Principal(id, parseType(m.get("type")), Boolean.TRUE.equals(m.get("verified")),
                strOrNull(m.get("idp")), strOrNull(m.get("username")),
                workloadId, strOrNull(m.get("identity_source")));
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
