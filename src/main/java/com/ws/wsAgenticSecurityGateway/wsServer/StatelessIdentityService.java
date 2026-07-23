package com.ws.wsAgenticSecurityGateway.wsServer;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.authConfig.repository.GatewayAuthConfigRepository;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Resolves and links identity for a STATELESS MCP request (Delta 2) — the per-request, in-memory equivalent
 * of what the session {@code initialize} + audit filter do, but with no handshake and no DB session row.
 *
 * <p>Identity is taken from the JWT (validated by {@code GatewayOAuth2Filter}, lifted onto the transport
 * context by {@code McpGatewayContextExtractor}). After {@link #bootstrap}, the synthetic-exchange flow
 * governs the request identically to session mode: tenant → STS mint, human → verified act_chain root,
 * agent → capability + governance + verified actor. {@link #cleanup} drops the per-request identity so no
 * state lingers.
 */
@Service
@Slf4j
public class StatelessIdentityService {

    private final AgentRegistryService agentRegistry;
    private final McpAuditService auditService;
    private final GatewayAuthConfigRepository authConfigRepository;

    /**
     * Grace before a per-request identity is evicted from the audit cache. The identity must outlive the
     * request itself, because the audit rows persist ASYNCHRONOUSLY (mcpAuditExecutor) and read the identity
     * (human, agent, tenant, roles) at persist time. Evicting synchronously on request return races that and
     * strips attribution off stateless rows. Defaults to 60s — orders of magnitude more than audit latency,
     * yet bounded (unique per-request key, self-expires → no leak).
     */
    @Value("${ws.gateway.stateless.identity-evict-grace-seconds:60}")
    private long evictGraceSeconds;

    private final ScheduledExecutorService evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stateless-identity-evict");
        t.setDaemon(true);
        return t;
    });

    public StatelessIdentityService(AgentRegistryService agentRegistry,
                                    McpAuditService auditService,
                                    GatewayAuthConfigRepository authConfigRepository) {
        this.agentRegistry = agentRegistry;
        this.auditService = auditService;
        this.authConfigRepository = authConfigRepository;
    }

    @PreDestroy
    void shutdown() {
        evictionScheduler.shutdownNow();
    }

    /**
     * Resolve + link identity for the per-request session id. Throws {@code AgentBlockedException} if the
     * agent is blocked or deprovisioned (governance at the door).
     */
    public void bootstrap(McpTransportContext ctx, String sessionId) {
        String jwtSubject = str(ctx.get("jwtSubject"));
        String clientId = str(ctx.get("agentClientId"));
        String tokenType = str(ctx.get("tokenType"));
        String idpIssuer = str(ctx.get("idpIssuer"));
        String tenant = resolveTenant(ctx, idpIssuer);

        // Carry the tenant in MDC so async audit rows can resolve it even after cleanup() evicts the
        // per-request identity: the audit executor's TaskDecorator snapshots MDC at submission time, and
        // orchestration (which submits those audits) runs on THIS thread, after this put.
        MDC.put("wsTenant", tenant);

        // Agent — identified by the JWT client_id (the authoritative caller id in stateless mode).
        UUID agentId = null;
        if (clientId != null) {
            GatewayAgentEntity agent = agentRegistry.discoverAgent(
                    clientId, "stateless", null, null, clientId, tokenType, tenant);
            agentId = agent.getId();
        }

        // Human — resolved only for human-delegated tokens, so the act_chain root is verified.
        UUID humanUserId = null;
        if ("HUMAN_DELEGATED".equalsIgnoreCase(tokenType) && jwtSubject != null) {
            GatewayHumanUserEntity human = agentRegistry.discoverHumanUser(
                    jwtSubject, str(ctx.get("userIdentity")), str(ctx.get("userEmail")),
                    str(ctx.get("userFullName")), str(ctx.get("userGivenName")), str(ctx.get("userFamilyName")),
                    idpIssuer, bool(ctx.get("userEmailVerified")),
                    strList(ctx.get("realmRoles")), strList(ctx.get("clientRoles")),
                    objMap(ctx.get("customClaims")), objMap(ctx.get("rawJwtClaims")),
                    str(ctx.get("clientIp")), tenant);
            humanUserId = human.getId();
        }

        agentRegistry.linkSessionIdentity(sessionId, agentId, humanUserId, null, jwtSubject);
        auditService.registerSessionIdentity(sessionId, new McpAuditService.AuditIdentityContext(
                tokenType, str(ctx.get("userIdentity")),
                humanUserId != null ? humanUserId.toString() : null,
                "HTTP-STATELESS", jwtSubject, clientId, strList(ctx.get("agentRoles")),
                null, str(ctx.get("clientIp")), tenant));

        log.debug("Stateless identity bootstrapped: session={}, tenant={}, agent={}, human={}",
                sessionId, tenant, agentId, humanUserId);
    }

    /**
     * Drop the per-request identity. The agent-map link and MDC are read only synchronously (act_chain,
     * capability check) so they go immediately. The audit identity is read asynchronously by the audit
     * executor, so its eviction is deferred by a grace window — otherwise stateless audit rows lose their
     * human/agent/tenant attribution to an eviction-vs-persist race.
     */
    public void cleanup(String sessionId) {
        agentRegistry.unlinkSession(sessionId);
        MDC.remove("wsTenant"); // clear the request thread; already-submitted async audits kept their snapshot
        long grace = Math.max(0, evictGraceSeconds);
        try {
            evictionScheduler.schedule(() -> auditService.evictSessionIdentity(sessionId), grace, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Scheduler unavailable (e.g. shutting down) — evict inline rather than leak the entry.
            auditService.evictSessionIdentity(sessionId);
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveTenant(McpTransportContext ctx, String idpIssuer) {
        Object headers = ctx.get("_httpHeaders");
        if (headers instanceof Map<?, ?> h) {
            Map<String, Object> hm = (Map<String, Object>) h;
            String tenant = str(hm.get("X-WS-Tenant"));
            if (tenant == null) tenant = str(hm.get("x-ws-tenant"));
            if (tenant != null) return tenant;
        }
        if (idpIssuer != null) {
            return authConfigRepository.findFirstByIssuerUri(idpIssuer)
                    .map(cfg -> cfg.getWsTenantName())
                    .orElse("default");
        }
        return "default";
    }

    private static String str(Object o) {
        return (o instanceof String s && !s.isBlank()) ? s : null;
    }

    private static Boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        return (o instanceof List<?> l) ? (List<String>) l : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
    }
}
