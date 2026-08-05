package com.ws.wsAgenticSecurityGateway.orchestration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityResult;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import com.ws.wsAgenticSecurityGateway.protocol.a2a.outbound.A2aAgentDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * A2A implementation of {@link ProtocolAdapter}: dispatches a governed SKILL hop to the resolved downstream
 * agent, attaching the per-hop OBO token (from {@link OboTokenHolder}) as the outbound {@code Authorization}
 * bearer — so the downstream agent receives a scoped, short-TTL delegation credential representing the governed
 * act_chain, not the caller's original token. This is the agent→agent analogue of {@code McpAdapter}.
 *
 * <p><b>Spec-native wire.</b> The outbound call is a hand-rolled A2A JSON-spec {@code message/send} over HTTP
 * (JSON-RPC 2.0), not routed through the a2a-java client's transport. The a2a-java 1.0.0.Final client
 * serializes protobuf-derived JSON (enum names like {@code ROLE_USER}, RPC method {@code SendMessage},
 * empty-string optional ids, a proto {@code oneof} response) that spec-compliant agents (e.g. the a2a-python
 * SDK) reject. Speaking the spec directly makes the gateway a first-class A2A caller that interoperates with
 * any spec agent, and keeps the OBO attachment fully under the gateway's control. The inbound boundary still
 * uses the a2a-java SDK types.
 *
 * <p>Registered under {@link #protocol()} = {@code "A2A"}; the spine's generic adapter router dispatches SKILL
 * hops here purely by that key. Tool/prompt/resource are MCP capabilities and are intentionally unsupported.
 */
@Service
@Slf4j
public class A2aAdapter implements ProtocolAdapter {

    private final A2aAgentDirectory directory;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Value("${ws.a2a.outbound.timeout-seconds:30}")
    private long timeoutSeconds;

    public A2aAdapter(A2aAgentDirectory directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String protocol() {
        return "A2A";
    }

    @Override
    public boolean isTargetConnected(String targetName) {
        return directory.isKnown(targetName);
    }

    @Override
    public String downstreamSessionId(String targetName) {
        return null; // A2A calls are per-message — there is no persistent downstream session id
    }

    @Override
    public boolean applyCredentials(Hop hop, String correlationId) {
        // The outbound credential for A2A is the per-hop OBO token, attached at send time from OboTokenHolder;
        // there is no agent-provided-token override to apply here.
        return false;
    }

    @Override
    public void clearCredentials(String correlationId) {
        // no-op — the OBO holder is cleared by the spine in the hop's finally
    }

    @Override
    public CapabilityResult invokeSkill(Hop hop, JsonNode argsJson, String correlationId,
                                        LocalDateTime firedAt, int eventSequence) {
        String agentName = hop.serverName();
        String skillId = hop.originalName();
        String endpoint = directory.resolve(agentName)
                .orElseThrow(() -> new IllegalStateException("Unknown A2A agent: " + agentName));

        String requestBody = buildMessageSend(skillId, extractInput(argsJson));
        String obo = OboTokenHolder.get();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (obo != null) {
            builder.header("Authorization", "Bearer " + obo);
        }

        try {
            log.info("[{}] A2A dispatch → agent='{}', skill='{}' (obo={})",
                    correlationId, agentName, skillId, obo != null ? "attached" : "none");
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return parseResult(response, agentName, skillId);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] A2A dispatch failed for agent '{}' skill '{}': {}",
                    correlationId, agentName, skillId, e.getMessage());
            throw new IllegalStateException("A2A skill invocation failed: " + e.getMessage(), e);
        }
    }

    /** Build a spec-compliant JSON-RPC {@code message/send} envelope targeting a skill via message metadata. */
    private String buildMessageSend(String skillId, String input) {
        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("kind", "message");
            message.put("role", "user");
            message.put("messageId", UUID.randomUUID().toString());
            ArrayNode parts = message.putArray("parts");
            ObjectNode textPart = parts.addObject();
            textPart.put("kind", "text");
            textPart.put("text", input);
            if (skillId != null && !skillId.isBlank()) {
                message.putObject("metadata").put("skillId", skillId);
            }

            ObjectNode params = mapper.createObjectNode();
            params.set("message", message);

            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", UUID.randomUUID().toString());
            envelope.put("method", "message/send");
            envelope.set("params", params);
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build A2A message/send request: " + e.getMessage(), e);
        }
    }

    private CapabilityResult parseResult(HttpResponse<String> response, String agentName, String skillId) {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("A2A skill invocation failed: HTTP " + response.statusCode()
                    + " from agent '" + agentName + "'");
        }
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("A2A skill invocation failed: unparseable response from '" + agentName + "'", e);
        }
        JsonNode error = body.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException("A2A skill invocation failed: " + error.path("message").asText("downstream A2A error"));
        }
        JsonNode result = body.get("result");
        String kind = result != null && result.hasNonNull("kind") ? result.get("kind").asText() : "message";
        return CapabilityResult.ok(result, extractText(result), 1, kind.toUpperCase(Locale.ROOT));
    }

    /** Pull the first text part out of a spec {@code Message} result, or a {@code Task}'s status message. */
    private static String extractText(JsonNode result) {
        if (result == null) {
            return "";
        }
        JsonNode parts = result.get("parts");
        if (parts == null || !parts.isArray()) {
            parts = result.path("status").path("message").get("parts"); // Task shape
        }
        if (parts != null && parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.hasNonNull("text")) {
                    return part.get("text").asText();
                }
            }
        }
        return "";
    }

    @Override
    public CapabilityResult callTool(Hop hop, JsonNode argsJson, String correlationId,
                                     LocalDateTime firedAt, int eventSequence) {
        throw new UnsupportedOperationException("A2A adapter handles SKILL, not TOOL");
    }

    @Override
    public CapabilityResult getPrompt(Hop hop) {
        throw new UnsupportedOperationException("A2A adapter handles SKILL, not PROMPT");
    }

    @Override
    public CapabilityResult readResource(Hop hop, String correlationId,
                                         LocalDateTime firedAt, int eventSequence) {
        throw new UnsupportedOperationException("A2A adapter handles SKILL, not RESOURCE");
    }

    private String extractInput(JsonNode argsJson) {
        if (argsJson == null) {
            return "";
        }
        JsonNode input = argsJson.get("input");
        return input != null && !input.isNull() ? input.asText() : argsJson.toString();
    }
}
