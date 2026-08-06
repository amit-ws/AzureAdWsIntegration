package com.ws.wsAgenticSecurityGateway.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Verifies the caller's {@code X-Agent-Assertion} — the agent's OWN workload-identity token (a Keycloak
 * client-credentials JWT it fetched for itself). This is the <em>actor credential</em> (RFC 8693): it proves
 * WHO is calling, independent of the delegated OBO it forwards (which proves ON WHOSE BEHALF).
 *
 * <p>Validation reuses {@link MultiIssuerJwtDecoder}, so it is a local signature check against the realm's
 * cached JWKS — no per-request network hop to the IdP. A missing header or an invalid assertion yields
 * {@code null}. Beyond the workload id (for the sender-constraint), it surfaces the agent's <em>own</em> roles
 * and groups: on a delegated hop the OBO carries none, so these are what let role/group policies (#5) evaluate
 * the calling agent. SPIFFE swaps in here later — verify an SVID against the trust bundle — with no caller change.
 */
@Component
@Slf4j
public class AgentAssertionVerifier {

    public static final String HEADER = "X-Agent-Assertion";

    /** A verified agent assertion: its workload id plus the agent's own realm roles and group memberships. */
    public record VerifiedAgent(String workloadId, List<String> roles, List<String> groups) {}

    private final MultiIssuerJwtDecoder decoder;

    public AgentAssertionVerifier(MultiIssuerJwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** The verified assertion (workload id + roles + groups), or {@code null} if absent/invalid. */
    @SuppressWarnings("unchecked")
    public VerifiedAgent verify(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            return null;
        }
        String token = header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim()
                : header.trim();
        try {
            Jwt jwt = decoder.decode(token);
            String azp = jwt.getClaimAsString("azp");
            if (azp == null || azp.isBlank()) {
                azp = jwt.getClaimAsString("client_id");
            }
            if (azp == null || azp.isBlank()) {
                return null;
            }
            return new VerifiedAgent(azp, realmRoles(jwt), groups(jwt));
        } catch (Exception e) {  // an unverifiable assertion is simply "no proven identity"
            log.warn("X-Agent-Assertion failed verification: {}", e.getMessage());
            return null;
        }
    }

    /** Convenience: just the verified workload id ({@code azp}), or {@code null}. */
    public String verifiedWorkloadId(HttpServletRequest request) {
        VerifiedAgent v = verify(request);
        return v != null ? v.workloadId() : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> ra && ra.get("roles") instanceof List<?> roles) {
            return roles.stream().map(String::valueOf).collect(Collectors.toList());
        }
        return List.of();
    }

    /** Group memberships, normalized (leading '/' stripped) to match {@code AgentGroup::"name"} policies. */
    private List<String> groups(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("groups");
        if (groups == null) {
            return List.of();
        }
        return groups.stream()
                .filter(g -> g != null && !g.isBlank())
                .map(g -> g.startsWith("/") ? g.substring(1) : g)
                .collect(Collectors.toList());
    }
}
