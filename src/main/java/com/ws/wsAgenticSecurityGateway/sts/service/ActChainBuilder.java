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
        List<Principal> chain = new ArrayList<>();

        String tokenType = str(c.get("tokenType"));
        String jwtSubject = str(c.get("jwtSubject"));
        String username = str(c.get("userIdentity"));
        String idp = str(c.get("idpIssuer"));
        String clientId = str(c.get("agentClientId"));

        UUID humanId = sessionId != null ? registry.getHumanUserIdForSession(sessionId) : null;
        UUID nhiId = sessionId != null ? registry.getNhiIdForSession(sessionId) : null;
        UUID agentId = sessionId != null ? registry.getAgentIdForSession(sessionId) : null;

        // ---- ROOT (index 0) ----
        boolean humanDelegated = "HUMAN_DELEGATED".equalsIgnoreCase(tokenType);
        if (humanDelegated && jwtSubject != null && humanId != null) {
            chain.add(Principal.human(jwtSubject, username, idp, true));   // strong, verified human root
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
        if (agentId != null) {
            chain.add(Principal.agent(agentId.toString(), clientId, clientId != null));
        } else if (clientId != null) {
            chain.add(Principal.agent(clientId, clientId, false));         // unresolved agent — use clientId, unverified
        }

        return new ActChain(chain);
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
