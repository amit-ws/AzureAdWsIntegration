package com.ws.wsAgenticSecurityGateway.sts.service;

import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import com.ws.wsAgenticSecurityGateway.sts.model.ActChain;
import com.ws.wsAgenticSecurityGateway.sts.model.MintRequest;
import com.ws.wsAgenticSecurityGateway.sts.model.MintedToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Bridges a hop to the STS: resolves the tenant, builds the {@code act_chain}, derives the per-hop
 * scope, mints an OBO token, and records {@code STS_TOKEN_MINTED}.
 *
 * <p>Behavior:
 * <ul>
 *   <li><b>Skip</b> (returns {@code null}) in open mode / when no tenant is resolved for the session —
 *       preserves the gateway's open-mode posture (mode=none) and keeps the MCP path unchanged there.</li>
 *   <li><b>Fail-closed</b> — when a tenant IS resolved but the mint fails, {@link StsService} throws
 *       {@link StsMintException}; the caller must deny the hop (Locked Decision 4).</li>
 * </ul>
 *
 * <p>On the external-MCP leg the minted token is an internal enforcement/audit artifact (it is not put
 * on the wire — the downstream credential stays brokered). Its value is the recorded {@code act_chain}
 * lineage + JIT scope + short TTL.
 */
@Service
@Slf4j
public class HopTokenMinter {

    private static final long DEFAULT_TTL_SECONDS = 120;

    private final ScopeDeriver scopeDeriver;
    private final StsService stsService;
    private final McpAuditService auditService;

    public HopTokenMinter(ScopeDeriver scopeDeriver,
                          StsService stsService,
                          McpAuditService auditService) {
        this.scopeDeriver = scopeDeriver;
        this.stsService = stsService;
        this.auditService = auditService;
    }

    /**
     * Mint a per-hop OBO token. Returns the token, or {@code null} if minting is skipped (open mode).
     * Throws {@link StsMintException} (fail-closed) if minting should happen but fails.
     */
    public MintedToken mintForHop(Hop hop, String sessionId, ActChain chain,
                                  String requestId, String correlationId, int eventSequence) {
        String tenant = auditService.resolveTenant(sessionId);
        if (tenant == null || tenant.isBlank() || "unknown".equals(tenant)) {
            return null; // open mode / no tenant resolved — skip minting
        }

        String capabilityType = hop.capabilityType() != null ? hop.capabilityType().name() : "TOOL";
        String scope = scopeDeriver.derive(hop.serverName(), hop.publicName(), capabilityType);

        MintedToken minted = stsService.mint(new MintRequest(
                tenant, chain, hop.serverName(), scope, hop.traceId(), correlationId, DEFAULT_TTL_SECONDS));

        auditService.auditStsTokenMinted(correlationId, sessionId, tenant, hop.serverName(),
                hop.publicName(), capabilityType, minted, DEFAULT_TTL_SECONDS, chain.toClaim(),
                requestId, LocalDateTime.now(), eventSequence);

        log.info("[{}] STS OBO token minted: tenant={}, aud={}, scope={}, jti={}, actChainLen={}",
                correlationId, tenant, hop.serverName(), scope, minted.jti(), chain.principals().size());
        return minted;
    }
}
