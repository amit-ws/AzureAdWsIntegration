package com.ws.wsAgenticSecurityGateway.protocol.a2a.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ws.wsAgenticSecurityGateway.orchestration.HopOrchestrator;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityResult;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityType;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestContext;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.protocol.a2a.wire.A2aRoleWire;
import com.ws.wsAgenticSecurityGateway.security.TenantResolver;
import com.ws.wsAgenticSecurityGateway.security.workload.WorkloadIdentity;
import com.ws.wsAgenticSecurityGateway.security.workload.WorkloadIdentitySource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The A2A inbound boundary — the agent→agent analogue of the MCP inbound facade. Serves the gateway's Agent
 * Card for discovery and handles the A2A {@code message/send} JSON-RPC call: it maps the message to a neutral
 * {@code Hop(SKILL)} and runs it through the same governance spine ({@link HopOrchestrator} — identity, OBO
 * mint, act_chain, PDP, audit, revocation), then maps the neutral {@link CapabilityResult} back into an A2A
 * {@link Task}.
 *
 * <p>The A2A wire types come from the SDK spec (JSON round-tripped via the SDK's Gson-backed {@link JsonUtil},
 * so the wire is spec-correct); the JSON-RPC envelope is handled with the gateway's Jackson mapper. The SDK's
 * server reference implementations are Quarkus/CDI-coupled, so this thin Spring MVC binding is the deliberate,
 * framework-appropriate integration point.
 */
@RestController
@Slf4j
public class A2aInboundController {

    private static final String METHOD_MESSAGE_SEND = "message/send";

    private final HopOrchestrator hopOrchestrator;
    private final A2aAgentCardService agentCardService;
    private final WorkloadIdentitySource workloadIdentitySource;
    private final TenantResolver tenantResolver;
    private final ObjectMapper objectMapper;

    public A2aInboundController(HopOrchestrator hopOrchestrator,
                               A2aAgentCardService agentCardService,
                               WorkloadIdentitySource workloadIdentitySource,
                               TenantResolver tenantResolver,
                               ObjectMapper objectMapper) {
        this.hopOrchestrator = hopOrchestrator;
        this.agentCardService = agentCardService;
        this.workloadIdentitySource = workloadIdentitySource;
        this.tenantResolver = tenantResolver;
        this.objectMapper = objectMapper;
    }

    /** Agent discovery (public, served at the spec's well-known root): the gateway's self-describing Agent Card. */
    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String agentCard() throws Exception {
        return JsonUtil.toJson(agentCardService.buildCard());
    }

    /**
     * The A2A JSON-RPC data-plane endpoint (authenticated — registered as a protected prefix by
     * {@code A2aTransportConfig}). Only {@code message/send} is handled; each call is governed as a SKILL hop.
     */
    @PostMapping(value = "/a2a", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String handle(@RequestBody String body, HttpServletRequest request) throws Exception {
        // Resolve the tenant for this request (header → token issuer via DB → "default") and carry it on the
        // request thread, so the spine's tenant-partitioned PDP and the STS OBO mint (which skips minting when
        // no tenant is resolved) see it — the session-less A2A plane has no per-session cache to read it from.
        TenantContext.set(tenantResolver.resolve(request));
        try {
            JsonNode envelope = objectMapper.readTree(body);
            JsonNode idNode = envelope.get("id");
            String method = envelope.path("method").asText("");

            if (!METHOD_MESSAGE_SEND.equals(method)) {
                log.warn("A2A: unsupported method '{}'", method);
                return jsonRpcError(idNode, -32601, "Method not found: " + method);
            }

            MessageSendParams params;
            try {
                JsonNode paramsNode = envelope.get("params");
                if (paramsNode != null) {
                    A2aRoleWire.toSdk(paramsNode); // spec role (user/agent) from the peer → SDK enum names for parsing
                }
                params = JsonUtil.fromJson(paramsNode != null ? paramsNode.toString() : "{}", MessageSendParams.class);
            } catch (Exception e) {
                log.warn("A2A: invalid message/send params: {}", e.getMessage());
                return jsonRpcError(idNode, -32602, "Invalid params: " + e.getMessage());
            }

            String skillName = A2aMessageMapper.resolveSkillName(params);
            if (skillName == null) {
                skillName = agentCardService.singleSkillOrNull();
            }
            if (skillName == null) {
                return jsonRpcError(idNode, -32602,
                        "No target skill: name it in message.metadata.skillId (the gateway fronts multiple skills)");
            }

            Map<String, Object> arguments = A2aMessageMapper.toArguments(params);
            // Establish the calling agent's identity through the pluggable seam (JWT today, SPIFFE later).
            WorkloadIdentity identity = workloadIdentitySource.resolve(request);
            RequestContext ctx = A2aRequestContextFactory.from(request, params.message(), identity);
            Hop hop = new Hop(CapabilityType.SKILL, skillName, arguments, null, ctx);

            CapabilityResult result = hopOrchestrator.handle(hop);
            Task task = A2aMessageMapper.toTask(params, result);
            return jsonRpcResult(idNode, JsonUtil.toJson(task));
        } finally {
            TenantContext.clear();
        }
    }

    private String jsonRpcResult(JsonNode id, String resultJson) throws Exception {
        ObjectNode env = objectMapper.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.set("id", id != null ? id : objectMapper.nullNode());
        JsonNode resultTree = objectMapper.readTree(resultJson);
        A2aRoleWire.toSpec(resultTree); // SDK enum names → spec role (user/agent) for the peer
        env.set("result", resultTree);
        return objectMapper.writeValueAsString(env);
    }

    private String jsonRpcError(JsonNode id, int code, String message) {
        ObjectNode env = objectMapper.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.set("id", id != null ? id : objectMapper.nullNode());
        ObjectNode error = env.putObject("error");
        error.put("code", code);
        error.put("message", message);
        try {
            return objectMapper.writeValueAsString(env);
        } catch (Exception e) {
            // Envelope is a plain ObjectNode we just built — serialization cannot realistically fail.
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"internal error\"}}";
        }
    }
}
