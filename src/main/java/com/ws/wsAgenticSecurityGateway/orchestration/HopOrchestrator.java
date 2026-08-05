package com.ws.wsAgenticSecurityGateway.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.error.GatewayErrorCode;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentCapabilityFilterService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.orchestration.adapter.OboTokenHolder;
import com.ws.wsAgenticSecurityGateway.orchestration.adapter.ProtocolAdapter;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityResult;
import com.ws.wsAgenticSecurityGateway.orchestration.model.ClientInfo;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestAttributeKeys;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestAttributes;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestContext;
import com.ws.wsAgenticSecurityGateway.sts.model.ActChain;
import com.ws.wsAgenticSecurityGateway.sts.model.MintedToken;
import com.ws.wsAgenticSecurityGateway.sts.service.ActChainBuilder;
import com.ws.wsAgenticSecurityGateway.sts.service.HopTokenMinter;
import lombok.Setter;
import org.slf4j.MDC;
import com.ws.wsAgenticSecurityGateway.sts.service.StsMintException;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationResult;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyContextBuilder;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.session.ClientSession;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.session.SessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The protocol-agnostic orchestration spine. Runs the governance lifecycle
 * (capability check → registry lookup → PDP → connection check → in-flight tracking →
 * audit) for every {@link Hop} and delegates the downstream protocol call + per-target
 * credentials to a {@link ProtocolAdapter}.
 *
 * <p>The three capability flows (tool / prompt / resource) are kept as explicit methods —
 * mirroring the existing gateway structure — because they differ in their PDP builder,
 * resolved descriptor field, result type, and error semantics (tools return an error
 * result; prompts/resources throw). This is byte-for-byte the behavior previously in
 * {@code ToolCallOrchestrator}; only the southbound call and credential handling now go
 * through the adapter.
 *
 * <p>The spine references no protocol SDK: it governs a neutral {@link RequestContext} on the way in
 * (built by each protocol's inbound boundary) and returns a neutral {@link CapabilityResult} on the way
 * out (mapped back to the wire type by that boundary). The MCP dependency lives entirely in the adapter
 * and the {@code protocol/mcp} boundary classes.
 */
@Service
@Slf4j
public class HopOrchestrator {

    private final CapabilityRegistryService registryService;
    private final GatewayAuditService auditService;
    private final InFlightRequestRegistry inFlightRegistry;
    private final ObjectMapper objectMapper;
    private final AgentRegistryService agentRegistryService;
    private final AgentCapabilityFilterService capabilityFilterService;
    private final CedarPolicyEngine cedarPolicyEngine;
    private final PolicyContextBuilder policyContextBuilder;
    /** The protocol dispatch seam: adapters keyed by {@link ProtocolAdapter#protocol()} — one entry per
     *  registered protocol (MCP today; A2A and partner connectors add themselves here with no spine change). */
    private final Map<String, ProtocolAdapter> adapterByProtocol;
    private final HopTokenMinter hopTokenMinter;
    private final ActChainBuilder actChainBuilder;

    @Setter
    private volatile SessionManager sessionManager;

    public HopOrchestrator(CapabilityRegistryService registryService,
                           GatewayAuditService auditService,
                           InFlightRequestRegistry inFlightRegistry,
                           ObjectMapper objectMapper,
                           AgentRegistryService agentRegistryService,
                           AgentCapabilityFilterService capabilityFilterService,
                           CedarPolicyEngine cedarPolicyEngine,
                           PolicyContextBuilder policyContextBuilder,
                           List<ProtocolAdapter> adapters,
                           HopTokenMinter hopTokenMinter,
                           ActChainBuilder actChainBuilder) {
        this.registryService = registryService;
        this.auditService = auditService;
        this.inFlightRegistry = inFlightRegistry;
        this.objectMapper = objectMapper;
        this.agentRegistryService = agentRegistryService;
        this.capabilityFilterService = capabilityFilterService;
        this.cedarPolicyEngine = cedarPolicyEngine;
        this.policyContextBuilder = policyContextBuilder;
        Map<String, ProtocolAdapter> byProtocol = new HashMap<>();
        for (ProtocolAdapter a : adapters) {
            byProtocol.put(a.protocol(), a);
        }
        this.adapterByProtocol = byProtocol;
        this.hopTokenMinter = hopTokenMinter;
        this.actChainBuilder = actChainBuilder;
    }

    /**
     * Dispatch a hop through the governance lifecycle. Returns a protocol-neutral {@link CapabilityResult};
     * the caller (the protocol's inbound facade) maps its {@code payload} back to its own wire type.
     */
    public CapabilityResult handle(Hop hop) {
        String traceId = resolveTraceId(hop);
        hop.setTraceId(traceId);
        MDC.put("traceId", traceId);
        try {
            return switch (hop.capabilityType()) {
                case TOOL -> handleToolCall(hop);
                case PROMPT -> handleGetPrompt(hop);
                case RESOURCE -> handleReadResource(hop);
                case SKILL -> handleSkillInvocation(hop);
            };
        } finally {
            MDC.remove("traceId");
            MDC.remove("correlationId");
        }
    }

    /**
     * The request-scoped trace id (umbrella over every leg). Honors an id the inbound request carried
     * (lifted onto the request attributes as {@link RequestAttributeKeys#TRACE_ID} by the protocol's inbound
     * boundary); otherwise generates one. Distinct from the per-leg {@code correlationId}, and identical
     * across a multi-hop tree.
     */
    private String resolveTraceId(Hop hop) {
        RequestContext rc = hop.requestContext();
        Object fromCtx = rc != null ? rc.attributes().get(RequestAttributeKeys.TRACE_ID) : null;
        if (fromCtx != null && !String.valueOf(fromCtx).isBlank()) {
            return String.valueOf(fromCtx);
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * The protocol dispatch seam. Selects the {@link ProtocolAdapter} for a hop by its resolved target protocol
     * ({@code MCP} / {@code A2A} / a partner-connector key). A new protocol registers its adapter (keyed by
     * {@link ProtocolAdapter#protocol()}) and is dispatched here with <em>no</em> change to the spine — this is
     * what lets A2A, gRPC, or a platform connector ride the same governance core. Falls back to the sole adapter
     * when only one is registered, so a single-protocol deployment needs no protocol stamped on its targets.
     */
    private ProtocolAdapter resolveAdapter(String protocol) {
        ProtocolAdapter adapter = protocol != null ? adapterByProtocol.get(protocol) : null;
        if (adapter != null) {
            return adapter;
        }
        if (adapterByProtocol.size() == 1) {
            return adapterByProtocol.values().iterator().next();
        }
        throw new IllegalStateException("No protocol adapter registered for '" + protocol
                + "' (registered: " + adapterByProtocol.keySet() + ")");
    }

    // ---------------------------------------------------------------------
    // TOOL
    // ---------------------------------------------------------------------

    private CapabilityResult handleToolCall(Hop hop) {
        RequestContext rc = hop.requestContext();
        String publicName = hop.publicName();

        String correlationId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("correlationId", correlationId);

        Object rawJsonRpcId = rc.attributes().get(RequestAttributeKeys.REQUEST_ID);
        String requestId = rawJsonRpcId != null ? String.valueOf(rawJsonRpcId) : null;

        String sessionId = resolveSessionId(rc);
        String clientName = resolveClientName(rc);

        agentRegistryService.recordRequest(sessionId);

        int seq = 0;

        GatewayErrorCode denialCode = governanceDenial(sessionId);
        if (denialCode != null) {
            log.warn("[{}] GOVERNANCE DENIED (defense-in-depth): session={}, tool={}, reason={}",
                    correlationId, sessionId, publicName, denialCode.getMessage());
            auditService.auditOrchestrationError(correlationId, sessionId, null, publicName,
                    denialCode, denialCode.getMessage(), requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildErrorResult(denialCode, publicName);
        }

        UUID agentIdForAccess = agentRegistryService.getAgentIdForSession(sessionId);
        if (agentIdForAccess != null
                && !capabilityFilterService.isCapabilityAllowed(agentIdForAccess, publicName, "TOOL")) {
            log.warn("[{}] CAPABILITY ACCESS DENIED: session={}, tool={}, agent={}",
                    correlationId, sessionId, publicName, clientName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    GatewayErrorCode.CAPABILITY_NOT_ALLOWED,
                    "Tool '" + publicName + "' is not permitted for this agent. "
                            + "Contact your gateway administrator to assign a capability profile.",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            auditService.auditCapabilityAccessDenied(sessionId, correlationId, clientName, publicName, "TOOL",
                    LocalDateTime.now(), ++seq);
            return buildErrorResult(GatewayErrorCode.CAPABILITY_NOT_ALLOWED, publicName);
        }
        if (agentIdForAccess != null && capabilityFilterService.hasProfiles(agentIdForAccess)) {
            auditService.auditCapabilityAccessGranted(sessionId, correlationId, clientName, publicName, "TOOL",
                    LocalDateTime.now(), ++seq);
        }

        log.info("Tool ORCHESTRATION START [{}]", correlationId);
        log.info("Tool: {}", publicName);
        log.info("Args: {}", hop.arguments() != null ? hop.arguments().keySet() : "null");
        log.info("Session: {}", sessionId != null ? sessionId : "unknown");
        log.info("Agent: {}", clientName);
        log.info("JSON-RPC ID: {}", requestId != null ? requestId : "n/a");

        long orchestrationStart = System.currentTimeMillis();

        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId, clientName,
                LocalDateTime.now(), ++seq);

        long lookupStart = System.currentTimeMillis();
        Optional<CapabilityDescriptor> optDescriptor = registryService.lookupByPublicName(publicName);
        long lookupDuration = System.currentTimeMillis() - lookupStart;

        if (optDescriptor.isEmpty()) {
            log.error("[{}] Tool '{}' NOT FOUND in capability registry", correlationId, publicName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    GatewayErrorCode.CAPABILITY_NOT_FOUND,
                    "Tool '" + publicName + "' not found in capability registry",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildErrorResult(GatewayErrorCode.CAPABILITY_NOT_FOUND, publicName);
        }

        CapabilityDescriptor descriptor = optDescriptor.get();
        String serverName = descriptor.getServerConfigName();
        String originalName = descriptor.getOriginalName();
        hop.resolve(serverName, originalName);

        log.info("[{}] Registry resolved: {} → server='{}', original='{}'",
                correlationId, publicName, serverName, originalName);

        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId, clientName,
                LocalDateTime.now(), ++seq);

        ActChain actChain = actChainBuilder.fromTransportContext(identityContext(rc), sessionId);

        try {
            PolicyEvaluationRequest pdpRequest = policyContextBuilder.buildForToolCall(
                    rc, publicName, serverName, originalName,
                    hop.arguments(), correlationId, sessionId);
            pdpRequest.setActChain(actChain.toClaim());

            auditService.auditPdpEvaluationRequested(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "toolCall", serverName, pdpRequest, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            PolicyEvaluationResult pdpResult = cedarPolicyEngine.evaluate(
                    auditService.resolveTenant(sessionId), pdpRequest);

            auditService.auditPdpDecisionRendered(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "toolCall", pdpResult.getDecision(),
                    serverName, pdpResult, pdpResult.getEvaluationDurationMs(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            if (pdpResult.isDenied()) {
                log.warn("[{}] PDP DENIED: agent='{}', tool='{}', reason='{}'",
                        correlationId, pdpRequest.getAgentName(), publicName, pdpResult.getReason());
                return buildErrorResult(GatewayErrorCode.PDP_DENIED,
                        publicName, serverName,
                        "Policy violation: " + pdpResult.getReason());
            }

            log.info("[{}] PDP ALLOWED: agent='{}', tool='{}' ({}ms)",
                    correlationId, pdpRequest.getAgentName(), publicName,
                    pdpResult.getEvaluationDurationMs());

        } catch (Exception e) {
            log.error("[{}] PDP evaluation error (fail-open): {}", correlationId, e.getMessage());
        }

        hop.setProtocol(descriptor.getProtocol());
        ProtocolAdapter adapter = resolveAdapter(hop.protocol());

        if (!adapter.isTargetConnected(serverName)) {
            log.error("[{}] Server '{}' is not connected — rejecting immediately", correlationId, serverName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.SERVER_UNAVAILABLE,
                    "Server '" + serverName + "' is not connected",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildErrorResult(GatewayErrorCode.SERVER_UNAVAILABLE,
                    publicName, serverName, "Server '" + serverName + "' is not connected");
        }

        String clientSessionId = null;
        clientSessionId = adapter.downstreamSessionId(serverName);

        String agentName = "unknown";
        String agentVersion = "";
        try {
            ClientInfo ci = rc.clientInfo();
            if (ci != null) {
                agentName = ci.name() != null ? ci.name() : "unknown";
                agentVersion = ci.version() != null ? ci.version() : "";
            }
        } catch (Exception ignored) {}
        inFlightRegistry.register(correlationId, publicName, serverName, originalName,
                sessionId, requestId, agentName, agentVersion);
        if (clientSessionId != null) {
            inFlightRegistry.updateClientSession(correlationId, clientSessionId);
        }

        JsonNode argsAsJson;
        try {
            argsAsJson = objectMapper.valueToTree(
                    hop.arguments() != null ? hop.arguments() : java.util.Map.of());
        } catch (Exception e) {
            log.error("[{}] Failed to convert arguments: {}", correlationId, e.getMessage());
            inFlightRegistry.fail(correlationId, "Argument conversion failed: " + e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.ORCHESTRATION_FAILURE,
                    "Argument conversion failed: " + e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildErrorResult(GatewayErrorCode.ORCHESTRATION_FAILURE,
                    publicName, serverName, "Argument conversion failed: " + e.getMessage());
        }

        String argsStr = argsAsJson.toString();
        inFlightRegistry.updateRequest(correlationId,
                argsStr.length() > 2000 ? argsStr.substring(0, 2000) + "..." : argsStr);

        MintedToken minted;
        try {
            minted = hopTokenMinter.mintForHop(hop, sessionId, actChain,
                    requestId, correlationId, ++seq);
        } catch (StsMintException e) {
            log.error("[{}] STS mint failed — denying tool (fail-closed): {}", correlationId, e.getMessage());
            inFlightRegistry.fail(correlationId, "STS mint failed: " + e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.PDP_DENIED, "Delegation token mint failed: " + e.getMessage(),
                    requestId, clientName, LocalDateTime.now(), ++seq);
            return buildErrorResult(GatewayErrorCode.PDP_DENIED, publicName, serverName,
                    "Delegation token unavailable");
        }

        boolean usingAgentToken = adapter.applyCredentials(hop, correlationId);
        inFlightRegistry.updateTokenMode(correlationId,
                minted != null ? "STS-OBO" : (usingAgentToken ? "AGENT-PROVIDED" : "CONFIG"));

        long callStart = System.currentTimeMillis();
        try {
            log.info("[{}] Forwarding to enterprise server '{}' → tool '{}' (token: {})",
                    correlationId, serverName, originalName,
                    usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

            CapabilityResult result =
                    adapter.callTool(hop, argsAsJson, correlationId, LocalDateTime.now(), ++seq);

            long callDuration = System.currentTimeMillis() - callStart;
            long totalDuration = System.currentTimeMillis() - orchestrationStart;

            inFlightRegistry.updateResponse(correlationId, result.summary(), result.itemCount(), result.itemTypes());
            inFlightRegistry.updateTimings(correlationId, lookupDuration, callDuration);

            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, originalName, callDuration, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            inFlightRegistry.complete(correlationId);

            agentRegistryService.updateLastActivity(sessionId);

            log.info("ORCHESTRATION COMPLETE [{}]", correlationId);
            log.info("Tool: {} → {}.{}", publicName, serverName, originalName);
            log.info("Response: {} content item(s)", result.itemCount());
            log.info("Forward: {}ms | Total: {}ms", callDuration, totalDuration);
            log.info("Token: {}", usingAgentToken ? "agent-provided" : "config-based");
            log.info("In-flight: {} active", inFlightRegistry.getActiveCount());

            return result;

        } catch (IllegalArgumentException e) {
            log.error("[{}] Server '{}' unavailable: {}", correlationId, serverName, e.getMessage());

            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.SERVER_UNAVAILABLE, e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            return buildErrorResult(GatewayErrorCode.SERVER_UNAVAILABLE,
                    publicName, serverName, e.getMessage());

        } catch (Exception e) {
            log.error("[{}] Orchestration failure for '{}' on '{}': {}",
                    correlationId, publicName, serverName, e.getMessage(), e);

            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.ORCHESTRATION_FAILURE, e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            return buildErrorResult(GatewayErrorCode.ORCHESTRATION_FAILURE,
                    publicName, serverName, e.getMessage());
        } finally {
            if (usingAgentToken) {
                adapter.clearCredentials(correlationId);
            }
        }
    }

    // ---------------------------------------------------------------------
    // SKILL (A2A agent→agent) — mirrors TOOL: args in, result out, error-as-result
    // ---------------------------------------------------------------------

    private CapabilityResult handleSkillInvocation(Hop hop) {
        RequestContext rc = hop.requestContext();
        String publicName = hop.publicName();

        String correlationId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("correlationId", correlationId);

        Object rawJsonRpcId = rc.attributes().get(RequestAttributeKeys.REQUEST_ID);
        String requestId = rawJsonRpcId != null ? String.valueOf(rawJsonRpcId) : null;

        String sessionId = resolveSessionId(rc);
        String clientName = resolveClientName(rc);

        agentRegistryService.recordRequest(sessionId);

        int seq = 0;

        GatewayErrorCode denialCode = governanceDenial(sessionId);
        if (denialCode != null) {
            log.warn("[{}] GOVERNANCE DENIED (defense-in-depth): session={}, skill={}, reason={}",
                    correlationId, sessionId, publicName, denialCode.getMessage());
            auditService.auditOrchestrationError(correlationId, sessionId, null, publicName,
                    denialCode, denialCode.getMessage(), requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildSkillError(denialCode, publicName);
        }

        UUID agentIdForAccess = agentRegistryService.getAgentIdForSession(sessionId);
        if (agentIdForAccess != null
                && !capabilityFilterService.isCapabilityAllowed(agentIdForAccess, publicName, "SKILL")) {
            log.warn("[{}] CAPABILITY ACCESS DENIED: session={}, skill={}, agent={}",
                    correlationId, sessionId, publicName, clientName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    GatewayErrorCode.CAPABILITY_NOT_ALLOWED,
                    "Skill '" + publicName + "' is not permitted for this agent. "
                            + "Contact your gateway administrator to assign a capability profile.",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            auditService.auditCapabilityAccessDenied(sessionId, correlationId, clientName, publicName, "SKILL",
                    LocalDateTime.now(), ++seq);
            return buildSkillError(GatewayErrorCode.CAPABILITY_NOT_ALLOWED, publicName);
        }
        if (agentIdForAccess != null && capabilityFilterService.hasProfiles(agentIdForAccess)) {
            auditService.auditCapabilityAccessGranted(sessionId, correlationId, clientName, publicName, "SKILL",
                    LocalDateTime.now(), ++seq);
        }

        log.info("Skill ORCHESTRATION START [{}]", correlationId);
        log.info("Skill: {}", publicName);
        log.info("Args: {}", hop.arguments() != null ? hop.arguments().keySet() : "null");
        log.info("Session: {}", sessionId != null ? sessionId : "unknown");
        log.info("Agent: {}", clientName);
        log.info("JSON-RPC ID: {}", requestId != null ? requestId : "n/a");

        long orchestrationStart = System.currentTimeMillis();

        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId, clientName,
                LocalDateTime.now(), ++seq);

        long lookupStart = System.currentTimeMillis();
        Optional<CapabilityDescriptor> optDescriptor = registryService.lookupByPublicName(publicName);
        long lookupDuration = System.currentTimeMillis() - lookupStart;

        if (optDescriptor.isEmpty()) {
            log.error("[{}] Skill '{}' NOT FOUND in capability registry", correlationId, publicName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    GatewayErrorCode.CAPABILITY_NOT_FOUND,
                    "Skill '" + publicName + "' not found in capability registry",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildSkillError(GatewayErrorCode.CAPABILITY_NOT_FOUND, publicName);
        }

        CapabilityDescriptor descriptor = optDescriptor.get();
        String serverName = descriptor.getServerConfigName();
        String originalName = descriptor.getOriginalName();
        hop.resolve(serverName, originalName);

        log.info("[{}] Registry resolved: {} → agent='{}', skill='{}'",
                correlationId, publicName, serverName, originalName);

        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId, clientName,
                LocalDateTime.now(), ++seq);

        ActChain actChain = actChainBuilder.fromTransportContext(identityContext(rc), sessionId);

        try {
            PolicyEvaluationRequest pdpRequest = policyContextBuilder.buildForSkillInvocation(
                    rc, publicName, serverName, originalName,
                    hop.arguments(), correlationId, sessionId);
            pdpRequest.setActChain(actChain.toClaim());

            auditService.auditPdpEvaluationRequested(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "skillInvocation", serverName, pdpRequest, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            PolicyEvaluationResult pdpResult = cedarPolicyEngine.evaluate(
                    auditService.resolveTenant(sessionId), pdpRequest);

            auditService.auditPdpDecisionRendered(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "skillInvocation", pdpResult.getDecision(),
                    serverName, pdpResult, pdpResult.getEvaluationDurationMs(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            if (pdpResult.isDenied()) {
                log.warn("[{}] PDP DENIED: agent='{}', skill='{}', reason='{}'",
                        correlationId, pdpRequest.getAgentName(), publicName, pdpResult.getReason());
                return buildSkillError(GatewayErrorCode.PDP_DENIED,
                        publicName, serverName,
                        "Policy violation: " + pdpResult.getReason());
            }

            log.info("[{}] PDP ALLOWED: agent='{}', skill='{}' ({}ms)",
                    correlationId, pdpRequest.getAgentName(), publicName,
                    pdpResult.getEvaluationDurationMs());

        } catch (Exception e) {
            log.error("[{}] PDP evaluation error (fail-open): {}", correlationId, e.getMessage());
        }

        hop.setProtocol(descriptor.getProtocol());
        ProtocolAdapter adapter = resolveAdapter(hop.protocol());

        if (!adapter.isTargetConnected(serverName)) {
            log.error("[{}] Agent '{}' is not connected — rejecting skill immediately", correlationId, serverName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.SERVER_UNAVAILABLE,
                    "Agent '" + serverName + "' is not connected",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildSkillError(GatewayErrorCode.SERVER_UNAVAILABLE,
                    publicName, serverName, "Agent '" + serverName + "' is not connected");
        }

        String clientSessionId = null;
        clientSessionId = adapter.downstreamSessionId(serverName);

        String agentName = "unknown";
        String agentVersion = "";
        try {
            ClientInfo ci = rc.clientInfo();
            if (ci != null) {
                agentName = ci.name() != null ? ci.name() : "unknown";
                agentVersion = ci.version() != null ? ci.version() : "";
            }
        } catch (Exception ignored) {}
        inFlightRegistry.register(correlationId, publicName, serverName, originalName,
                sessionId, requestId, agentName, agentVersion);
        if (clientSessionId != null) {
            inFlightRegistry.updateClientSession(correlationId, clientSessionId);
        }

        JsonNode argsAsJson;
        try {
            argsAsJson = objectMapper.valueToTree(
                    hop.arguments() != null ? hop.arguments() : java.util.Map.of());
        } catch (Exception e) {
            log.error("[{}] Failed to convert arguments: {}", correlationId, e.getMessage());
            inFlightRegistry.fail(correlationId, "Argument conversion failed: " + e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.ORCHESTRATION_FAILURE,
                    "Argument conversion failed: " + e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildSkillError(GatewayErrorCode.ORCHESTRATION_FAILURE,
                    publicName, serverName, "Argument conversion failed: " + e.getMessage());
        }

        String argsStr = argsAsJson.toString();
        inFlightRegistry.updateRequest(correlationId,
                argsStr.length() > 2000 ? argsStr.substring(0, 2000) + "..." : argsStr);

        MintedToken minted;
        try {
            minted = hopTokenMinter.mintForHop(hop, sessionId, actChain,
                    requestId, correlationId, ++seq);
        } catch (StsMintException e) {
            log.error("[{}] STS mint failed — denying skill (fail-closed): {}", correlationId, e.getMessage());
            inFlightRegistry.fail(correlationId, "STS mint failed: " + e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.PDP_DENIED, "Delegation token mint failed: " + e.getMessage(),
                    requestId, clientName, LocalDateTime.now(), ++seq);
            return buildSkillError(GatewayErrorCode.PDP_DENIED, publicName, serverName,
                    "Delegation token unavailable");
        }

        // A2A puts the OBO token on the wire (agent→agent) — the adapter reads it from the holder at dispatch,
        // attaching it as the outbound bearer so the downstream agent gets a scoped delegation credential.
        OboTokenHolder.set(minted != null ? minted.token() : null);

        boolean usingAgentToken = adapter.applyCredentials(hop, correlationId);
        inFlightRegistry.updateTokenMode(correlationId,
                minted != null ? "STS-OBO" : (usingAgentToken ? "AGENT-PROVIDED" : "CONFIG"));

        long callStart = System.currentTimeMillis();
        try {
            log.info("[{}] Forwarding to downstream agent '{}' → skill '{}' (token: {})",
                    correlationId, serverName, originalName,
                    usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

            CapabilityResult result =
                    adapter.invokeSkill(hop, argsAsJson, correlationId, LocalDateTime.now(), ++seq);

            long callDuration = System.currentTimeMillis() - callStart;
            long totalDuration = System.currentTimeMillis() - orchestrationStart;

            inFlightRegistry.updateResponse(correlationId, result.summary(), result.itemCount(), result.itemTypes());
            inFlightRegistry.updateTimings(correlationId, lookupDuration, callDuration);

            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, originalName, callDuration, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            inFlightRegistry.complete(correlationId);

            agentRegistryService.updateLastActivity(sessionId);

            log.info("SKILL ORCHESTRATION COMPLETE [{}]", correlationId);
            log.info("Skill: {} → {}.{}", publicName, serverName, originalName);
            log.info("Response: {} content item(s)", result.itemCount());
            log.info("Forward: {}ms | Total: {}ms", callDuration, totalDuration);
            log.info("Token: {}", usingAgentToken ? "agent-provided" : "config-based");
            log.info("In-flight: {} active", inFlightRegistry.getActiveCount());

            return result;

        } catch (IllegalArgumentException e) {
            log.error("[{}] Agent '{}' unavailable: {}", correlationId, serverName, e.getMessage());

            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.SERVER_UNAVAILABLE, e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            return buildSkillError(GatewayErrorCode.SERVER_UNAVAILABLE,
                    publicName, serverName, e.getMessage());

        } catch (Exception e) {
            log.error("[{}] Orchestration failure for skill '{}' on '{}': {}",
                    correlationId, publicName, serverName, e.getMessage(), e);

            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.ORCHESTRATION_FAILURE, e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            return buildSkillError(GatewayErrorCode.ORCHESTRATION_FAILURE,
                    publicName, serverName, e.getMessage());
        } finally {
            OboTokenHolder.clear();
            if (usingAgentToken) {
                adapter.clearCredentials(correlationId);
            }
        }
    }

    // ---------------------------------------------------------------------
    // PROMPT
    // ---------------------------------------------------------------------

    private CapabilityResult handleGetPrompt(Hop hop) {
        RequestContext rc = hop.requestContext();
        String publicName = hop.publicName();

        String correlationId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("correlationId", correlationId);
        Object rawJsonRpcId = rc.attributes().get(RequestAttributeKeys.REQUEST_ID);
        String requestId = rawJsonRpcId != null ? String.valueOf(rawJsonRpcId) : null;
        String sessionId = resolveSessionId(rc);
        String clientName = resolveClientName(rc);
        agentRegistryService.recordRequest(sessionId);
        int seq = 0;

        GatewayErrorCode denialCode = governanceDenial(sessionId);
        if (denialCode != null) {
            log.warn("[{}] GOVERNANCE DENIED (defense-in-depth): session={}, prompt={}, reason={}",
                    correlationId, sessionId, publicName, denialCode.getMessage());
            auditService.auditOrchestrationError(correlationId, sessionId, null, publicName,
                    denialCode, denialCode.getMessage(), requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: prompt '%s'",
                    denialCode.getCode(), denialCode.getMessage(), publicName));
        }

        UUID promptAgentId = agentRegistryService.getAgentIdForSession(sessionId);
        if (promptAgentId != null
                && !capabilityFilterService.isCapabilityAllowed(promptAgentId, publicName, "PROMPT")) {
            log.warn("[{}] CAPABILITY ACCESS DENIED: session={}, prompt={}, agent={}",
                    correlationId, sessionId, publicName, clientName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    GatewayErrorCode.CAPABILITY_NOT_ALLOWED,
                    "Prompt '" + publicName + "' is not permitted for this agent.",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            auditService.auditCapabilityAccessDenied(sessionId, correlationId, clientName, publicName, "PROMPT",
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: prompt '%s'",
                    GatewayErrorCode.CAPABILITY_NOT_ALLOWED.getCode(),
                    GatewayErrorCode.CAPABILITY_NOT_ALLOWED.getMessage(), publicName));
        }
        if (promptAgentId != null && capabilityFilterService.hasProfiles(promptAgentId)) {
            auditService.auditCapabilityAccessGranted(sessionId, correlationId, clientName, publicName, "PROMPT",
                    LocalDateTime.now(), ++seq);
        }

        log.info("PROMPT ORCHESTRATION START [{}]", correlationId);
        log.info("Prompt: {}", publicName);
        log.info("Args: {}", hop.arguments() != null ? hop.arguments().keySet() : "null");
        log.info("Session: {}", sessionId != null ? sessionId : "unknown");
        log.info("Agent: {}", clientName);
        log.info("JSON-RPC ID: {}", requestId != null ? requestId : "n/a");

        long orchestrationStart = System.currentTimeMillis();

        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId, clientName,
                LocalDateTime.now(), ++seq);

        long lookupStart = System.currentTimeMillis();
        Optional<CapabilityDescriptor> optDescriptor = registryService.lookupByPublicName(publicName);
        long lookupDuration = System.currentTimeMillis() - lookupStart;

        if (optDescriptor.isEmpty()) {
            log.error("[{}] Prompt '{}' NOT FOUND in capability registry", correlationId, publicName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    GatewayErrorCode.CAPABILITY_NOT_FOUND,
                    "Prompt '" + publicName + "' not found in capability registry",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: prompt '%s'",
                    GatewayErrorCode.CAPABILITY_NOT_FOUND.getCode(),
                    GatewayErrorCode.CAPABILITY_NOT_FOUND.getMessage(), publicName));
        }

        CapabilityDescriptor descriptor = optDescriptor.get();
        String serverName = descriptor.getServerConfigName();
        String originalName = descriptor.getOriginalName();
        hop.resolve(serverName, originalName);

        log.info("[{}] Registry resolved: {} → server='{}', original='{}'",
                correlationId, publicName, serverName, originalName);

        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId, clientName,
                LocalDateTime.now(), ++seq);

        ActChain actChain = actChainBuilder.fromTransportContext(identityContext(rc), sessionId);

        try {
            PolicyEvaluationRequest pdpRequest = policyContextBuilder.buildForPromptGet(
                    rc, publicName, serverName, originalName,
                    correlationId, sessionId);
            pdpRequest.setActChain(actChain.toClaim());

            auditService.auditPdpEvaluationRequested(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "promptGet", serverName, pdpRequest, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            PolicyEvaluationResult pdpResult = cedarPolicyEngine.evaluate(
                    auditService.resolveTenant(sessionId), pdpRequest);

            auditService.auditPdpDecisionRendered(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "promptGet", pdpResult.getDecision(),
                    serverName, pdpResult, pdpResult.getEvaluationDurationMs(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            if (pdpResult.isDenied()) {
                log.warn("[{}] PDP DENIED: agent='{}', prompt='{}', reason='{}'",
                        correlationId, pdpRequest.getAgentName(), publicName, pdpResult.getReason());
                throw new RuntimeException(String.format("[%d] Policy violation: %s",
                        GatewayErrorCode.PDP_DENIED.getCode(), pdpResult.getReason()));
            }

            log.info("[{}] PDP ALLOWED: agent='{}', prompt='{}' ({}ms)",
                    correlationId, pdpRequest.getAgentName(), publicName,
                    pdpResult.getEvaluationDurationMs());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] PDP evaluation error (fail-open): {}", correlationId, e.getMessage());
        }

        hop.setProtocol(descriptor.getProtocol());
        ProtocolAdapter adapter = resolveAdapter(hop.protocol());

        if (!adapter.isTargetConnected(serverName)) {
            log.error("[{}] Server '{}' is not connected — rejecting prompt immediately", correlationId, serverName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.SERVER_UNAVAILABLE,
                    "Server '" + serverName + "' is not connected",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: server '%s' is not connected",
                    GatewayErrorCode.SERVER_UNAVAILABLE.getCode(),
                    GatewayErrorCode.SERVER_UNAVAILABLE.getMessage(), serverName));
        }

        String pClientSessionId = null;
        pClientSessionId = adapter.downstreamSessionId(serverName);

        String pAgentName = "unknown";
        String pAgentVersion = "";
        try {
            ClientInfo ci = rc.clientInfo();
            if (ci != null) {
                pAgentName = ci.name() != null ? ci.name() : "unknown";
                pAgentVersion = ci.version() != null ? ci.version() : "";
            }
        } catch (Exception ignored) {}
        inFlightRegistry.register(correlationId, publicName, serverName, originalName,
                sessionId, requestId, pAgentName, pAgentVersion);
        if (pClientSessionId != null) {
            inFlightRegistry.updateClientSession(correlationId, pClientSessionId);
        }

        try {
            String pArgsStr = objectMapper.writeValueAsString(hop.arguments() != null ? hop.arguments() : java.util.Map.of());
            inFlightRegistry.updateRequest(correlationId,
                    pArgsStr.length() > 2000 ? pArgsStr.substring(0, 2000) + "..." : pArgsStr);
        } catch (Exception ignored) {}

        MintedToken minted;
        try {
            minted = hopTokenMinter.mintForHop(hop, sessionId, actChain,
                    requestId, correlationId, ++seq);
        } catch (StsMintException e) {
            log.error("[{}] STS mint failed — denying prompt (fail-closed): {}", correlationId, e.getMessage());
            inFlightRegistry.fail(correlationId, "STS mint failed: " + e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.PDP_DENIED, "Delegation token mint failed: " + e.getMessage(),
                    requestId, clientName, LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: prompt '%s' — delegation token unavailable",
                    GatewayErrorCode.PDP_DENIED.getCode(), GatewayErrorCode.PDP_DENIED.getMessage(), publicName));
        }

        boolean usingAgentToken = adapter.applyCredentials(hop, correlationId);
        inFlightRegistry.updateTokenMode(correlationId,
                minted != null ? "STS-OBO" : (usingAgentToken ? "AGENT-PROVIDED" : "CONFIG"));

        long callStart = System.currentTimeMillis();
        try {
            log.info("[{}] Forwarding getPrompt to '{}' → prompt '{}' (token: {})",
                    correlationId, serverName, originalName,
                    usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

            CapabilityResult result = adapter.getPrompt(hop);

            long callDuration = System.currentTimeMillis() - callStart;
            long totalDuration = System.currentTimeMillis() - orchestrationStart;

            inFlightRegistry.updateResponse(correlationId, result.summary(), result.itemCount(), result.itemTypes());
            inFlightRegistry.updateTimings(correlationId, lookupDuration, callDuration);

            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, originalName, callDuration, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            inFlightRegistry.complete(correlationId);

            agentRegistryService.updateLastActivity(sessionId);

            int messageCount = result.itemCount();
            log.info("PROMPT ORCHESTRATION COMPLETE [{}]", correlationId);
            log.info("Prompt: {} → {}.{}", publicName, serverName, originalName);
            log.info("Response: {} message(s)", messageCount);
            log.info("Forward: {}ms | Total: {}ms", callDuration, totalDuration);
            log.info("In-flight: {} active", inFlightRegistry.getActiveCount());

            return result;

        } catch (IllegalArgumentException e) {
            log.error("[{}] Server '{}' unavailable: {}", correlationId, serverName, e.getMessage());
            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.SERVER_UNAVAILABLE, e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: prompt '%s' on server '%s' — %s",
                    GatewayErrorCode.SERVER_UNAVAILABLE.getCode(),
                    GatewayErrorCode.SERVER_UNAVAILABLE.getMessage(),
                    publicName, serverName, e.getMessage()), e);

        } catch (Exception e) {
            log.error("[{}] Prompt orchestration failure for '{}' on '{}': {}",
                    correlationId, publicName, serverName, e.getMessage(), e);
            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.ORCHESTRATION_FAILURE, e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: prompt '%s' on server '%s' — %s",
                    GatewayErrorCode.ORCHESTRATION_FAILURE.getCode(),
                    GatewayErrorCode.ORCHESTRATION_FAILURE.getMessage(),
                    publicName, serverName, e.getMessage()), e);
        } finally {
            if (usingAgentToken) {
                adapter.clearCredentials(correlationId);
            }
        }
    }

    // ---------------------------------------------------------------------
    // RESOURCE
    // ---------------------------------------------------------------------

    private CapabilityResult handleReadResource(Hop hop) {
        RequestContext rc = hop.requestContext();
        String publicName = hop.publicName();
        String resourceUri = hop.resourceUri();

        String correlationId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("correlationId", correlationId);
        Object rawJsonRpcId = rc.attributes().get(RequestAttributeKeys.REQUEST_ID);
        String requestId = rawJsonRpcId != null ? String.valueOf(rawJsonRpcId) : null;
        String sessionId = resolveSessionId(rc);
        String clientName = resolveClientName(rc);
        agentRegistryService.recordRequest(sessionId);
        int seq = 0;

        GatewayErrorCode denialCode = governanceDenial(sessionId);
        if (denialCode != null) {
            log.warn("[{}] GOVERNANCE DENIED (defense-in-depth): session={}, resource={}, reason={}",
                    correlationId, sessionId, publicName, denialCode.getMessage());
            auditService.auditOrchestrationError(correlationId, sessionId, null, publicName,
                    denialCode, denialCode.getMessage(), requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: resource '%s'",
                    denialCode.getCode(), denialCode.getMessage(), publicName));
        }

        UUID resourceAgentId = agentRegistryService.getAgentIdForSession(sessionId);
        if (resourceAgentId != null
                && !capabilityFilterService.isCapabilityAllowed(resourceAgentId, publicName, "RESOURCE")) {
            log.warn("[{}] CAPABILITY ACCESS DENIED: session={}, resource={}, agent={}",
                    correlationId, sessionId, publicName, clientName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    GatewayErrorCode.CAPABILITY_NOT_ALLOWED,
                    "Resource '" + publicName + "' is not permitted for this agent.",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            auditService.auditCapabilityAccessDenied(sessionId, correlationId, clientName, publicName, "RESOURCE",
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: resource '%s'",
                    GatewayErrorCode.CAPABILITY_NOT_ALLOWED.getCode(),
                    GatewayErrorCode.CAPABILITY_NOT_ALLOWED.getMessage(), publicName));
        }
        if (resourceAgentId != null && capabilityFilterService.hasProfiles(resourceAgentId)) {
            auditService.auditCapabilityAccessGranted(sessionId, correlationId, clientName, publicName, "RESOURCE",
                    LocalDateTime.now(), ++seq);
        }

        log.info("RESOURCE ORCHESTRATION START [{}]", correlationId);
        log.info("Resource: {}", publicName);
        log.info("URI: {}", resourceUri);
        log.info("Session: {}", sessionId != null ? sessionId : "unknown");
        log.info("Agent: {}", clientName);
        log.info("JSON-RPC ID: {}", requestId != null ? requestId : "n/a");

        long orchestrationStart = System.currentTimeMillis();

        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId, clientName,
                LocalDateTime.now(), ++seq);

        long lookupStart = System.currentTimeMillis();
        Optional<CapabilityDescriptor> optDescriptor = registryService.lookupByPublicName(publicName);
        long lookupDuration = System.currentTimeMillis() - lookupStart;

        if (optDescriptor.isEmpty()) {
            log.error("[{}] Resource '{}' NOT FOUND in capability registry", correlationId, publicName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    GatewayErrorCode.CAPABILITY_NOT_FOUND,
                    "Resource '" + publicName + "' not found in capability registry",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: resource '%s'",
                    GatewayErrorCode.CAPABILITY_NOT_FOUND.getCode(),
                    GatewayErrorCode.CAPABILITY_NOT_FOUND.getMessage(), publicName));
        }

        CapabilityDescriptor descriptor = optDescriptor.get();
        String serverName = descriptor.getServerConfigName();
        String originalUri = descriptor.getResourceUri();
        hop.resolve(serverName, originalUri);

        log.info("[{}] Registry resolved: {} → server='{}', uri='{}'",
                correlationId, publicName, serverName, originalUri);

        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId, clientName,
                LocalDateTime.now(), ++seq);

        ActChain actChain = actChainBuilder.fromTransportContext(identityContext(rc), sessionId);

        try {
            PolicyEvaluationRequest pdpRequest = policyContextBuilder.buildForResourceRead(
                    rc, publicName, serverName, descriptor.getOriginalName(),
                    correlationId, sessionId);
            pdpRequest.setActChain(actChain.toClaim());

            auditService.auditPdpEvaluationRequested(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "resourceRead", serverName, pdpRequest, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            PolicyEvaluationResult pdpResult = cedarPolicyEngine.evaluate(
                    auditService.resolveTenant(sessionId), pdpRequest);

            auditService.auditPdpDecisionRendered(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "resourceRead", pdpResult.getDecision(),
                    serverName, pdpResult, pdpResult.getEvaluationDurationMs(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            if (pdpResult.isDenied()) {
                log.warn("[{}] PDP DENIED: agent='{}', resource='{}', reason='{}'",
                        correlationId, pdpRequest.getAgentName(), publicName, pdpResult.getReason());
                throw new RuntimeException(String.format("[%d] Policy violation: %s",
                        GatewayErrorCode.PDP_DENIED.getCode(), pdpResult.getReason()));
            }

            log.info("[{}] PDP ALLOWED: agent='{}', resource='{}' ({}ms)",
                    correlationId, pdpRequest.getAgentName(), publicName,
                    pdpResult.getEvaluationDurationMs());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] PDP evaluation error (fail-open): {}", correlationId, e.getMessage());
        }

        hop.setProtocol(descriptor.getProtocol());
        ProtocolAdapter adapter = resolveAdapter(hop.protocol());

        if (!adapter.isTargetConnected(serverName)) {
            log.error("[{}] Server '{}' is not connected — rejecting resource read immediately", correlationId, serverName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.SERVER_UNAVAILABLE,
                    "Server '" + serverName + "' is not connected",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: server '%s' is not connected",
                    GatewayErrorCode.SERVER_UNAVAILABLE.getCode(),
                    GatewayErrorCode.SERVER_UNAVAILABLE.getMessage(), serverName));
        }

        String rClientSessionId = null;
        rClientSessionId = adapter.downstreamSessionId(serverName);

        String rAgentName = "unknown";
        String rAgentVersion = "";
        try {
            ClientInfo ci = rc.clientInfo();
            if (ci != null) {
                rAgentName = ci.name() != null ? ci.name() : "unknown";
                rAgentVersion = ci.version() != null ? ci.version() : "";
            }
        } catch (Exception ignored) {}
        inFlightRegistry.register(correlationId, publicName, serverName, originalUri,
                sessionId, requestId, rAgentName, rAgentVersion);
        if (rClientSessionId != null) {
            inFlightRegistry.updateClientSession(correlationId, rClientSessionId);
        }

        inFlightRegistry.updateRequest(correlationId, resourceUri != null ? resourceUri : "");

        MintedToken minted;
        try {
            minted = hopTokenMinter.mintForHop(hop, sessionId, actChain,
                    requestId, correlationId, ++seq);
        } catch (StsMintException e) {
            log.error("[{}] STS mint failed — denying resource (fail-closed): {}", correlationId, e.getMessage());
            inFlightRegistry.fail(correlationId, "STS mint failed: " + e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.PDP_DENIED, "Delegation token mint failed: " + e.getMessage(),
                    requestId, clientName, LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: resource '%s' — delegation token unavailable",
                    GatewayErrorCode.PDP_DENIED.getCode(), GatewayErrorCode.PDP_DENIED.getMessage(), publicName));
        }

        boolean usingAgentToken = adapter.applyCredentials(hop, correlationId);
        inFlightRegistry.updateTokenMode(correlationId,
                minted != null ? "STS-OBO" : (usingAgentToken ? "AGENT-PROVIDED" : "CONFIG"));

        long callStart = System.currentTimeMillis();
        try {
            log.info("[{}] Forwarding readResource to '{}' → uri '{}' (token: {})",
                    correlationId, serverName, originalUri,
                    usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

            CapabilityResult result =
                    adapter.readResource(hop, correlationId, LocalDateTime.now(), ++seq);

            long callDuration = System.currentTimeMillis() - callStart;
            long totalDuration = System.currentTimeMillis() - orchestrationStart;

            inFlightRegistry.updateResponse(correlationId, result.summary(), result.itemCount(), result.itemTypes());
            inFlightRegistry.updateTimings(correlationId, lookupDuration, callDuration);

            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, publicName, callDuration, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            inFlightRegistry.complete(correlationId);

            agentRegistryService.updateLastActivity(sessionId);

            log.info("RESOURCE ORCHESTRATION COMPLETE [{}]", correlationId);
            log.info("Resource: {} → {}", publicName, originalUri);
            log.info("Response: {} content item(s)", result.itemCount());
            log.info("Forward: {}ms | Total: {}ms", callDuration, totalDuration);
            log.info("In-flight: {} active", inFlightRegistry.getActiveCount());

            return result;

        } catch (IllegalArgumentException e) {
            log.error("[{}] Server '{}' unavailable: {}", correlationId, serverName, e.getMessage());
            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.SERVER_UNAVAILABLE, e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: resource '%s' on server '%s' — %s",
                    GatewayErrorCode.SERVER_UNAVAILABLE.getCode(),
                    GatewayErrorCode.SERVER_UNAVAILABLE.getMessage(),
                    publicName, serverName, e.getMessage()), e);

        } catch (Exception e) {
            log.error("[{}] Resource orchestration failure for '{}' on '{}': {}",
                    correlationId, publicName, serverName, e.getMessage(), e);
            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    GatewayErrorCode.ORCHESTRATION_FAILURE, e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: resource '%s' on server '%s' — %s",
                    GatewayErrorCode.ORCHESTRATION_FAILURE.getCode(),
                    GatewayErrorCode.ORCHESTRATION_FAILURE.getMessage(),
                    publicName, serverName, e.getMessage()), e);
        } finally {
            if (usingAgentToken) {
                adapter.clearCredentials(correlationId);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Shared helpers (moved verbatim from ToolCallOrchestrator)
    // ---------------------------------------------------------------------

    private String resolveSessionId(RequestContext rc) {
        try {
            if (sessionManager != null) {
                ClientSession cs = sessionManager.getCurrentSession();
                if (cs != null) {
                    String id = cs.getSessionId();
                    if (id != null && !id.isBlank()) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve session ID from SessionManager: {}", e.getMessage());
        }

        try {
            if (rc != null) {
                String id = rc.sessionId();
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }
        } catch (Exception e) {
            log.debug("Could not get session ID from request context: {}", e.getMessage());
        }
        return null;
    }

    private String resolveClientName(RequestContext rc) {
        try {
            if (rc != null) {
                ClientInfo clientInfo = rc.clientInfo();
                if (clientInfo != null) {
                    String name = clientInfo.name() != null ? clientInfo.name() : "unknown";
                    String version = clientInfo.version() != null ? " v" + clientInfo.version() : "";
                    return name + version;
                }
                // Stateless bridge: no clientInfo on the synthetic request — fall back to the JWT client_id
                // so logs/audit name the caller instead of "unknown".
                Object tcClientId = rc.attributes().get(RequestAttributeKeys.AGENT_CLIENT_ID);
                if (tcClientId instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve client info from request context: {}", e.getMessage());
        }
        return "unknown";
    }

    private static final List<String> IDENTITY_KEYS = List.of(
            RequestAttributeKeys.JWT_SUBJECT, RequestAttributeKeys.USER_IDENTITY,
            RequestAttributeKeys.AGENT_CLIENT_ID, RequestAttributeKeys.IDP_ISSUER,
            RequestAttributeKeys.TOKEN_TYPE, RequestAttributeKeys.RAW_JWT_CLAIMS);

    /**
     * Extract the per-request identity context — read from the request's attribute bag
     * (captured once per request), NOT the shared-mutable {@code SessionManager.getCurrentSession()}
     * (a concurrency hazard). This is the identity source for the act_chain (Locked Decision 5).
     */
    private Map<String, Object> identityContext(RequestContext rc) {
        Map<String, Object> ctx = new HashMap<>();
        if (rc == null) {
            return ctx;
        }
        try {
            RequestAttributes attributes = rc.attributes();
            for (String key : IDENTITY_KEYS) {
                Object v = attributes.get(key);
                if (v != null) {
                    ctx.put(key, v);
                }
            }
        } catch (Exception e) {
            log.debug("Could not read identity context from request context: {}", e.getMessage());
        }
        return ctx;
    }

    /**
     * Defense-in-depth governance gate (LD-5). The inbound {@code HttpMcpAuditFilter} already refuses blocked
     * / pending / deprovisioned agents; this is a second check at the orchestration layer so those agents are
     * refused even on any execution path that does not traverse the servlet filter. Returns the refusal code,
     * or {@code null} to proceed. Unknown/APPROVED status proceeds — the inbound filter is the primary gate,
     * so this secondary check never turns a missing status into a false denial.
     */
    private GatewayErrorCode governanceDenial(String sessionId) {
        if ("DEPROVISIONED".equals(agentRegistryService.getAgentLifecycleStatusForSession(sessionId))) {
            return GatewayErrorCode.AGENT_DEPROVISIONED;
        }
        String approval = agentRegistryService.getAgentStatusForSession(sessionId);
        if ("BLOCKED".equals(approval)) {
            return GatewayErrorCode.AGENT_BLOCKED;
        }
        if ("PENDING".equals(approval)) {
            return GatewayErrorCode.AGENT_PENDING_APPROVAL;
        }
        return null;
    }

    private CapabilityResult buildErrorResult(GatewayErrorCode errorCode,
                                              String publicName) {
        return capabilityError(errorCode, "tool", publicName, null, null);
    }

    private CapabilityResult buildErrorResult(GatewayErrorCode errorCode,
                                              String publicName,
                                              String serverName,
                                              String detail) {
        return capabilityError(errorCode, "tool", publicName, serverName, detail);
    }

    private CapabilityResult buildSkillError(GatewayErrorCode errorCode,
                                             String publicName) {
        return capabilityError(errorCode, "skill", publicName, null, null);
    }

    private CapabilityResult buildSkillError(GatewayErrorCode errorCode,
                                             String publicName,
                                             String serverName,
                                             String detail) {
        return capabilityError(errorCode, "skill", publicName, serverName, detail);
    }

    /** Neutral error-result string for a failed capability dispatch, worded by {@code kind} ({@code tool}/{@code skill}). */
    private CapabilityResult capabilityError(GatewayErrorCode errorCode, String kind,
                                             String publicName, String serverName, String detail) {
        String message = serverName == null
                ? String.format("[%d] %s: %s '%s'",
                        errorCode.getCode(), errorCode.getMessage(), kind, publicName)
                : String.format("[%d] %s: %s '%s' on server '%s' — %s",
                        errorCode.getCode(), errorCode.getMessage(), kind, publicName, serverName, detail);
        return CapabilityResult.error(message);
    }
}
