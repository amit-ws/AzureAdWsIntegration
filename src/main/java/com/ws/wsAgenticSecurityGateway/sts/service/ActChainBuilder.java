package com.ws.wsAgenticSecurityGateway.sts.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.sts.model.ActChain;
import com.ws.wsAgenticSecurityGateway.sts.model.Principal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@link ActChain} delegation lineage for a hop from the per-exchange identity context
 * (the keys {@code McpGatewayContextExtractor} lifts onto the MCP transport context) plus the session.
 *
 * <p>Order: root (human / NHI / unverified) → optional prior actor seeded from the inbound RFC 8693
 * {@code act} claim → the calling agent (actor). Uses the stable, verified identifiers the registry
 * resolves — the agent's UUID and the human/NHI UUID — not the self-asserted MCP {@code clientInfo}
 * name. Never fabricates a verified root: a weak/inferred human is emitted {@code verified=false}.
 */
@Component
public class ActChainBuilder {

    private final AgentRegistryService registry;

    public ActChainBuilder(AgentRegistryService registry) {
        this.registry = registry;
    }

    public ActChain fromTransportContext(Map<String, Object> ctx, String sessionId) {
        Map<String, Object> c = ctx != null ? ctx : Map.of();

        String clientId = str(c.get("agentClientId"));
        String tokenType = str(c.get("tokenType"));
        UUID agentId = sessionId != null ? registry.getAgentIdForSession(sessionId) : null;

        // The calling agent's identity is verified when it was cryptographically established: a registry-bound
        // session agent, or an agent named by a validated token. tokenType is only assigned in oauth2 mode,
        // after the resource server validated the inbound token, so its presence is that proof — the session-
        // less data planes (A2A / stateless) rely on it since they have no server-side session to bind to.
        boolean tokenValidated = tokenType != null && !tokenType.isBlank();

        // ---- MULTI-HOP: extend the inbound gateway act_chain if the caller presented one ----
        // A downstream A2A leg arrives bearing a gateway-minted OBO whose {@code act_chain} already holds the
        // verified root + every prior actor. Extend it — append this hop's calling agent — rather than
        // rebuilding a root. For a first hop / MCP (an external IdP token has no {@code act_chain} claim) this
        // is empty, so the original root-building path below runs and its behavior is unchanged.
        ActChain inboundChain = ActChain.fromClaim(inboundClaim(c, "act_chain"));
        if (!inboundChain.isEmpty()) {
            List<Principal> extended = new ArrayList<>(inboundChain.principals());
            appendActor(extended, agentId, clientId, tokenValidated);
            return new ActChain(extended);
        }

        List<Principal> chain = new ArrayList<>();

        String jwtSubject = str(c.get("jwtSubject"));
        String username = str(c.get("userIdentity"));
        String idp = str(c.get("idpIssuer"));

        UUID humanId = sessionId != null ? registry.getHumanUserIdForSession(sessionId) : null;
        UUID nhiId = sessionId != null ? registry.getNhiIdForSession(sessionId) : null;

        // ---- ROOT (index 0) ----
        // A verified human root requires a validated human-delegated token (HUMAN_DELEGATED is only assigned
        // in oauth2 mode, after the resource server validated the token) that identifies a KNOWN human —
        // either a registry-bound session (humanId, the MCP data plane) or an IdP-asserted username (the A2A
        // data plane / stateless, which carry no server-side session). A bare, unidentifiable subject is never
        // fabricated into a verified root: it falls through to the weak branch below.
        boolean humanDelegated = "HUMAN_DELEGATED".equalsIgnoreCase(tokenType);
        boolean knownHuman = humanId != null || (username != null && !username.isBlank());
        if (humanDelegated && jwtSubject != null && knownHuman) {
            chain.add(Principal.human(jwtSubject, username, idp, true));   // verified human root
        } else if (nhiId != null) {
            chain.add(Principal.nhi(nhiId.toString(), true));              // machine-rooted (NHI)
        } else if (jwtSubject != null) {
            chain.add(Principal.human(jwtSubject, username, idp, false));  // inferred/weak — never fabricated
        } else {
            chain.add(Principal.unknownRoot(idp));                         // no identifiable root
        }

        // ---- SEED: inbound RFC 8693 `act` claim (an existing delegation), if present ----
        String priorActor = str(inboundActSub(c));
        if (priorActor != null) {
            chain.add(Principal.agent(priorActor, null, false));           // from the inbound token claim, unverified
        }

        // ---- ACTOR (the calling agent) ----
        appendActor(chain, agentId, clientId, tokenValidated);

        return new ActChain(chain);
    }

    /** Append the calling agent as the chain's actor, skipping a duplicate of the current last principal. */
    private static void appendActor(List<Principal> chain, UUID agentId, String clientId, boolean tokenValidated) {
        Principal actor;
        if (agentId != null) {
            actor = Principal.agent(agentId.toString(), clientId, clientId != null);
        } else if (clientId != null) {
            // Session-less caller (A2A / stateless): the agent is verified when named by a validated token.
            actor = Principal.agent(clientId, clientId, tokenValidated);
        } else {
            return;
        }
        Principal last = chain.isEmpty() ? null : chain.get(chain.size() - 1);
        if (last == null || !actor.id().equals(last.id())) {
            chain.add(actor);
        }
    }

    private static Object inboundClaim(Map<String, Object> ctx, String key) {
        Object raw = ctx.get("rawJwtClaims");
        return raw instanceof Map<?, ?> claims ? claims.get(key) : null;
    }

    @SuppressWarnings("unchecked")
    private static Object inboundActSub(Map<String, Object> ctx) {
        Object raw = ctx.get("rawJwtClaims");
        if (raw instanceof Map<?, ?> claims) {
            Object act = ((Map<String, Object>) claims).get("act");
            if (act instanceof Map<?, ?> actMap) {
                return ((Map<String, Object>) actMap).get("sub");
            }
        }
        return null;
    }

    private static String str(Object o) {
        return (o instanceof String s && !s.isBlank()) ? s : null;
    }
}
