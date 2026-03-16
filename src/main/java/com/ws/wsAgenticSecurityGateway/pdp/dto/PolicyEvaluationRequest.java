package com.ws.wsAgenticSecurityGateway.pdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Structured input for Cedar policy evaluation.
 *
 * <p>Maps directly to Cedar's PARC model:
 * <ul>
 *   <li><b>Principal</b> — the agent requesting access</li>
 *   <li><b>Action</b> — what the agent wants to do</li>
 *   <li><b>Resource</b> — the tool/prompt/resource being accessed</li>
 *   <li><b>Context</b> — transient request-scoped data</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyEvaluationRequest {

    // ── Principal (Agent) ─────────────────────────────────────────────
    private String agentName;
    private String agentVersion;
    private String agentApprovalStatus;
    private String agentSessionId;

    // ── Action ────────────────────────────────────────────────────────
    /** The MCP method: "toolCall", "promptGet", "resourceRead", etc. */
    private String action;

    // ── Resource (Tool / Prompt / Resource) ────────────────────────────
    /** Public namespaced name (e.g., "github_create_issue"). */
    private String resourceName;
    /** Enterprise MCP server name (e.g., "github"). */
    private String serverName;
    /** Original name on the server (e.g., "create_issue"). */
    private String originalName;
    /** Capability type: TOOL, PROMPT, RESOURCE. */
    private String resourceType;

    // ── Context (environment + request data) ──────────────────────────
    /** Tool call arguments as key-value pairs (only for tool calls). */
    private Map<String, Object> arguments;
    /** Correlation ID linking this to the orchestration chain. */
    private String correlationId;
    /** Source IP of the agent's HTTP connection. */
    private String sourceIp;

    // ── Custom attributes (ABAC extensibility) ──────────────────────
    /**
     * Additional custom attributes to include in policy evaluation context.
     * These are merged into the context attribute map, enabling policies
     * like {@code context.riskScore > 50} or {@code context.timezone == "UTC"}
     * without any engine code changes.
     *
     * <p>Populate via {@link PolicyContextBuilder} or external attribute providers.
     */
    private Map<String, Object> customAttributes;
}
