package com.ws.wsAgenticSecurityGateway.protocol.a2a.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Normalizes the A2A {@code Message.role} enum across the wire dialect gap between the a2a-java SDK and the
 * A2A JSON spec.
 *
 * <p><b>Why this exists.</b> The a2a-java 1.0.0.Final SDK (which the gateway uses to (de)serialize A2A
 * messages) represents {@code role} with the protobuf enum names — {@code "ROLE_USER"} / {@code "ROLE_AGENT"}
 * — because its Gson has no {@code @SerializedName} mapping for the enum. The A2A JSON spec (and every
 * spec-compliant peer, e.g. the a2a-python SDK) uses the lowercase {@code "user"} / {@code "agent"}. Left
 * unbridged, a spec client's message parses to {@code role=null} in the gateway (constructor failure), and a
 * message the gateway sends to a spec agent is rejected. The SDK's shared {@code JsonUtil.OBJECT_MAPPER} is a
 * {@code final} Gson built by a private builder, so a custom {@code TypeAdapter} cannot be injected — hence the
 * gateway normalizes at its JSON boundary instead.
 *
 * <p>The rewrite is surgical: it only ever rewrites the value of a JSON field named exactly {@code "role"}
 * whose value is one of the known enum tokens; every other field (including free-text that happens to contain
 * "user"/"agent") is left byte-for-byte untouched. Anything that is not JSON, or carries no {@code role}, is
 * returned unchanged.
 *
 * <ul>
 *   <li>{@link #toSdk} — spec → SDK ({@code user}→{@code ROLE_USER}). Applied to bytes the gateway is about to
 *       hand to the SDK for parsing: an inbound request from a peer, or a response coming back from an agent.</li>
 *   <li>{@link #toSpec} — SDK → spec ({@code ROLE_USER}→{@code user}). Applied to bytes the SDK produced that
 *       are about to leave the gateway toward a spec peer: the response to the caller, or a request to an agent.</li>
 * </ul>
 */
public final class A2aRoleWire {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROLE = "role";

    /** Spec role tokens → a2a-java SDK enum names. */
    private static final Map<String, String> SPEC_TO_SDK = Map.of(
            "user", "ROLE_USER",
            "agent", "ROLE_AGENT");

    /** a2a-java SDK enum names → spec role tokens ({@code ROLE_UNSPECIFIED} normalizes to {@code user}). */
    private static final Map<String, String> SDK_TO_SPEC = Map.of(
            "ROLE_USER", "user",
            "ROLE_AGENT", "agent",
            "ROLE_UNSPECIFIED", "user");

    private A2aRoleWire() {
    }

    /** Rewrite spec role tokens → SDK enum names in a JSON string (pass-through if not JSON / no role). */
    public static String toSdk(String json) {
        return rewriteString(json, SPEC_TO_SDK);
    }

    /** Rewrite SDK enum names → spec role tokens in a JSON string (pass-through if not JSON / no role). */
    public static String toSpec(String json) {
        return rewriteString(json, SDK_TO_SPEC);
    }

    /** In-place: rewrite spec role tokens → SDK enum names throughout a parsed JSON tree. */
    public static void toSdk(JsonNode node) {
        rewrite(node, SPEC_TO_SDK);
    }

    /** In-place: rewrite SDK enum names → spec role tokens throughout a parsed JSON tree. */
    public static void toSpec(JsonNode node) {
        rewrite(node, SDK_TO_SPEC);
    }

    private static String rewriteString(String json, Map<String, String> mapping) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            rewrite(root, mapping);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // Not JSON, or not ours to touch — hand the original bytes through untouched.
            return json;
        }
    }

    private static void rewrite(JsonNode node, Map<String, String> mapping) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            JsonNode roleValue = obj.get(ROLE);
            if (roleValue != null && roleValue.isTextual()) {
                String mapped = mapping.get(roleValue.asText());
                if (mapped != null) {
                    obj.put(ROLE, mapped);
                }
            }
            for (Iterator<JsonNode> it = obj.elements(); it.hasNext(); ) {
                rewrite(it.next(), mapping);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                rewrite(child, mapping);
            }
        }
    }
}
