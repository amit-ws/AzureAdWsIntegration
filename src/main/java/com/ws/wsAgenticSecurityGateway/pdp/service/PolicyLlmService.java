package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyChatRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyChatResponse;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * LLM-powered Cedar policy generation service.
 *
 * <p>Converts natural-language admin prompts into Cedar policy code using
 * the Anthropic Claude API (Messages endpoint).
 *
 * <h3>Features</h3>
 * <ul>
 *   <li><b>Live metadata injection</b> — system prompt includes actual agents, servers, tools</li>
 *   <li><b>Structured schema</b> — operators, sources, grammar shared with LLM</li>
 *   <li><b>Multi-turn conversation</b> — iterative refinement via conversation history</li>
 *   <li><b>Auto-validation</b> — validates generated Cedar before returning</li>
 *   <li><b>Template fallback</b> — works without API key (demo mode)</li>
 * </ul>
 */
@Service
@Slf4j
public class PolicyLlmService {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-20250514";
    private static final int MAX_TOKENS = 2048;
    private static final long METADATA_CACHE_TTL_MS = 60_000; // 60 seconds

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AgentRegistryService agentRegistryService;
    private final CapabilityRegistryService capabilityRegistryService;
    private final PolicyService policyService;
    private final CedarPolicyEngine cedarEngine;

    @Value("${ws.gateway.pdp.anthropic-api-key:}")
    private String anthropicApiKey;

    // ── Metadata cache ──────────────────────────────────────────────────
    private final Map<String, CachedValue> metadataCache = new ConcurrentHashMap<>();

    private record CachedValue(String data, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > METADATA_CACHE_TTL_MS;
        }
    }

    public PolicyLlmService(ObjectMapper objectMapper,
                             AgentRegistryService agentRegistryService,
                             CapabilityRegistryService capabilityRegistryService,
                             PolicyService policyService,
                             CedarPolicyEngine cedarEngine) {
        this.objectMapper = objectMapper;
        this.agentRegistryService = agentRegistryService;
        this.capabilityRegistryService = capabilityRegistryService;
        this.policyService = policyService;
        this.cedarEngine = cedarEngine;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Check if LLM mode is available (API key configured).
     */
    public boolean isLlmAvailable() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }

    // ════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Generate a Cedar policy from a natural-language admin prompt.
     * Supports both single-shot and multi-turn conversation modes.
     *
     * @param request the chat request (prompt or messages)
     * @return generated policy response with Cedar code, or follow-up question
     */
    public PolicyChatResponse generatePolicy(PolicyChatRequest request) {
        String effectivePrompt = request.getEffectivePrompt();
        if (effectivePrompt == null || effectivePrompt.isBlank()) {
            return PolicyChatResponse.error("Prompt cannot be empty");
        }

        if (!isLlmAvailable()) {
            log.info("🤖 LLM not configured — using template fallback for: {}", effectivePrompt);
            return generateFromTemplate(effectivePrompt);
        }

        try {
            log.info("🤖 Calling Anthropic API for policy generation: {}", effectivePrompt);
            return callAnthropicApi(request);
        } catch (Exception e) {
            log.error("🤖 Anthropic API call failed: {} — falling back to template", e.getMessage());
            return generateFromTemplate(effectivePrompt);
        }
    }

    /**
     * Single-shot convenience method (backward compatible).
     */
    public PolicyChatResponse generatePolicy(String prompt) {
        PolicyChatRequest request = PolicyChatRequest.builder().prompt(prompt).build();
        return generatePolicy(request);
    }

    // ════════════════════════════════════════════════════════════════════
    //  ANTHROPIC API INTEGRATION
    // ════════════════════════════════════════════════════════════════════

    private PolicyChatResponse callAnthropicApi(PolicyChatRequest chatRequest) throws Exception {
        String systemPrompt = buildSystemPrompt();

        // Build messages array — multi-turn or single-shot
        List<Map<String, String>> messages;
        if (chatRequest.isMultiTurn()) {
            messages = chatRequest.getMessages().stream()
                    .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                    .collect(Collectors.toList());
        } else {
            messages = List.of(Map.of("role", "user", "content", chatRequest.getPrompt()));
        }

        Map<String, Object> requestBody = Map.of(
                "model", MODEL,
                "max_tokens", MAX_TOKENS,
                "system", systemPrompt,
                "messages", messages
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", anthropicApiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("🤖 Anthropic API returned {}: {}", response.statusCode(), response.body());
            return PolicyChatResponse.error("Anthropic API error: HTTP " + response.statusCode());
        }

        return parseAnthropicResponse(response.body(), chatRequest.getEffectivePrompt());
    }

    private PolicyChatResponse parseAnthropicResponse(String responseBody, String originalPrompt) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("content");
            if (content.isArray() && !content.isEmpty()) {
                String text = content.get(0).path("text").asText("");

                // Check if LLM is asking a follow-up question (no Cedar block)
                String policyText = extractCedarBlock(text);
                if (policyText == null || policyText.isBlank()) {
                    // No Cedar block → LLM is asking for more info
                    return PolicyChatResponse.builder()
                            .success(true)
                            .conversationComplete(false)
                            .followUpQuestion(text.trim())
                            .source("LLM_GENERATED")
                            .build();
                }

                // Cedar block found → policy generated
                String policyName = extractField(text, "POLICY_NAME:");
                String description = extractField(text, "DESCRIPTION:");
                String effect = extractField(text, "EFFECT:");
                String explanation = extractField(text, "EXPLANATION:");

                // Auto-validate the generated Cedar
                String validationError = cedarEngine.validatePolicy(policyText);
                if (validationError != null) {
                    log.warn("🤖 LLM generated invalid Cedar: {}", validationError);
                }

                return PolicyChatResponse.builder()
                        .success(true)
                        .policyText(policyText)
                        .suggestedName(policyName != null ? policyName : generatePolicyName(originalPrompt))
                        .description(description != null ? description : originalPrompt)
                        .effect(effect != null ? effect : detectEffect(policyText))
                        .explanation(explanation)
                        .source("LLM_GENERATED")
                        .conversationComplete(true)
                        .validationError(validationError)
                        .build();
            }

            return PolicyChatResponse.error("Empty response from Anthropic API");

        } catch (Exception e) {
            log.error("🤖 Failed to parse Anthropic response: {}", e.getMessage());
            return PolicyChatResponse.error("Failed to parse LLM response: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  SYSTEM PROMPT (schema + live metadata)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Build the complete system prompt: static schema + live metadata.
     *
     * <p>The static schema teaches the LLM exactly what our Cedar engine supports.
     * The live metadata tells it what agents, servers, and tools actually exist.
     */
    private String buildSystemPrompt() {
        return buildStaticSchema() + "\n\n" + buildLiveMetadataSection();
    }

    /**
     * Static schema — the structured grammar and rules our engine supports.
     * This never changes at runtime.
     */
    private String buildStaticSchema() {
        return """
                You are a policy generation assistant for the WS Agentic Security Gateway.
                You generate Cedar authorization policies based on natural-language admin descriptions.

                ## Engine Schema (STRICT — only generate syntax listed here)

                ### Policy Structure
                ```
                @id("<policy-id>")
                <effect>(
                    <principal_constraint>,
                    <action_constraint>,
                    <resource_constraint>
                )
                [when { <conditions> }]
                [unless { <conditions> }]
                ;
                ```

                ### Effects
                - `permit` — allow access
                - `forbid` — deny access (overrides permit)

                ### Principal Constraints (pick one)
                - `principal` — any principal (no constraint)
                - `principal is Agent` — any agent (type constraint)
                - `principal == Agent::"<name>"` — specific agent (entity equality)

                ### Action Constraints (pick one)
                - `action` — any action (no constraint)
                - `action == Action::"<action>"` — specific action
                - `action in [Action::"<a>", Action::"<b>"]` — action set

                Valid actions: `toolCall`, `promptGet`, `resourceRead`

                ### Resource Constraints (pick one)
                - `resource` — any resource (no constraint)
                - `resource is Tool` / `resource is Prompt` / `resource is Resource` — type constraint
                - `resource == Tool::"<name>"` — specific tool (entity equality)
                - `resource == Prompt::"<name>"` — specific prompt
                - `resource == Resource::"<name>"` — specific resource

                ### Hierarchy (in when/unless blocks)
                - `principal in AgentGroup::"APPROVED"` — agent approval group
                - `principal in AgentGroup::"PENDING"`
                - `principal in AgentGroup::"BLOCKED"`
                - `resource in Server::"<server_name>"` — resource belongs to server

                ### Attribute Sources
                Three attribute maps are available. ALL operators work with ALL sources.

                | Source | Syntax | Built-in attributes |
                |--------|--------|---------------------|
                | `principal` | `principal.X` | name, version, approvalStatus, sessionId |
                | `resource` | `resource.X` | name, serverName, originalName, type |
                | `context` | `context.X` | hour (0-23), minute (0-59), dayOfWeek (MONDAY..SUNDAY), month (JANUARY..DECEMBER), year, businessHours (bool), sourceIp, serverName, resourceName, correlationId, argumentsFlat |

                Custom attributes can be used with any source (e.g., `context.riskScore`, `context.timezone`).

                ### Operators (use in when/unless blocks)
                | Operator | Syntax | Example |
                |----------|--------|---------|
                | EQ | `source.attr == value` | `context.hour == 9`, `principal.version == "2.0"` |
                | NEQ | `source.attr != value` | `context.dayOfWeek != "SATURDAY"` |
                | GT | `source.attr > N` | `context.hour > 17` |
                | LT | `source.attr < N` | `context.hour < 8` |
                | GTE | `source.attr >= N` | `context.hour >= 8` |
                | LTE | `source.attr <= N` | `context.hour <= 22` |
                | LIKE | `source.attr like "pattern"` | `context.argumentsFlat like "*production*"` |
                | CONTAINS | `source.attr.contains("val")` | `context.argumentsFlat.contains("secret")` |

                Value types: `"string"` (quoted), `123` / `-5` (integer, no quotes), `true`/`false` (boolean, no quotes)
                Wildcard: `*` matches any sequence of characters in `like` patterns.

                ### Combining Conditions
                Use `&&` to combine multiple conditions in when/unless blocks.
                All conditions must be true (AND semantics).

                ```cedar
                when {
                    principal in AgentGroup::"APPROVED" &&
                    resource in Server::"github" &&
                    context.businessHours == true &&
                    context.argumentsFlat like "*safe*"
                }
                ```

                ## Response Format

                When you have enough information to generate a policy, respond with:

                POLICY_NAME: <short-kebab-case-name>
                DESCRIPTION: <one-sentence description>
                EFFECT: <PERMIT or FORBID>
                EXPLANATION: <brief explanation of what this policy does>

                ```cedar
                <the cedar policy code>
                ```

                When you need more information, ask a clear follow-up question WITHOUT any Cedar code block.

                ## Rules
                1. Every policy MUST have an @id annotation matching the POLICY_NAME
                2. ONLY use syntax listed in the Engine Schema above — do NOT invent new operators or constructs
                3. Cedar is default-deny — if no permit matches, the request is denied
                4. `forbid` always overrides `permit`
                5. Use `when` for positive conditions and `unless` for exceptions
                6. Keep policies focused — one concern per policy
                7. Use exact entity names from the Live System Metadata section below when available
                8. If the admin's request is ambiguous, ask a follow-up question instead of guessing
                9. Always validate that referenced agents, servers, and tools exist in the live metadata
                """;
    }

    /**
     * Build live metadata section from actual system state.
     * Cached for 60 seconds to avoid per-request DB/registry overhead.
     */
    private String buildLiveMetadataSection() {
        CachedValue cached = metadataCache.get("liveMetadata");
        if (cached != null && !cached.isExpired()) {
            return cached.data();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Live System Metadata (current state of the gateway)\n\n");

        // ── Registered Agents ──
        try {
            List<GatewayAgentEntity> agents = agentRegistryService.getAllAgents();
            sb.append("### Registered Agents\n");
            if (agents == null || agents.isEmpty()) {
                sb.append("No agents registered yet.\n\n");
            } else {
                sb.append("| Agent Name | Version | Approval Status |\n");
                sb.append("|------------|---------|----------------|\n");
                for (GatewayAgentEntity agent : agents) {
                    sb.append("| `").append(agent.getAgentName()).append("` | ")
                            .append(agent.getAgentVersion() != null ? agent.getAgentVersion() : "-").append(" | ")
                            .append(agent.getApprovalStatus()).append(" |\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("### Registered Agents\nUnable to fetch agent data.\n\n");
            log.debug("🤖 Could not fetch agents for LLM metadata: {}", e.getMessage());
        }

        // ── Connected Servers + Tools ──
        try {
            Set<String> servers = capabilityRegistryService.getRegisteredServerNames();
            sb.append("### Connected Servers\n");
            if (servers == null || servers.isEmpty()) {
                sb.append("No servers connected yet.\n\n");
            } else {
                for (String server : servers) {
                    sb.append("- `").append(server).append("`\n");
                }
                sb.append("\n");

                sb.append("### Available Tools\n");
                List<CapabilityDescriptor> tools = capabilityRegistryService.getToolDescriptors();
                if (tools != null && !tools.isEmpty()) {
                    sb.append("| Tool (public name) | Server | Original Name |\n");
                    sb.append("|--------------------|--------|---------------|\n");
                    for (CapabilityDescriptor tool : tools) {
                        sb.append("| `").append(tool.getPublicName()).append("` | ")
                                .append(tool.getServerConfigName()).append(" | ")
                                .append(tool.getOriginalName()).append(" |\n");
                    }
                } else {
                    sb.append("No tools registered.\n");
                }
                sb.append("\n");

                List<CapabilityDescriptor> prompts = capabilityRegistryService.getPromptDescriptors();
                if (prompts != null && !prompts.isEmpty()) {
                    sb.append("### Available Prompts\n");
                    for (CapabilityDescriptor p : prompts) {
                        sb.append("- `").append(p.getPublicName()).append("` (server: ").append(p.getServerConfigName()).append(")\n");
                    }
                    sb.append("\n");
                }

                List<CapabilityDescriptor> resources = capabilityRegistryService.getResourceDescriptors();
                if (resources != null && !resources.isEmpty()) {
                    sb.append("### Available Resources\n");
                    for (CapabilityDescriptor r : resources) {
                        sb.append("- `").append(r.getPublicName()).append("` (server: ").append(r.getServerConfigName()).append(")\n");
                    }
                    sb.append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("### Connected Servers\nUnable to fetch server data.\n\n");
            log.debug("🤖 Could not fetch capabilities for LLM metadata: {}", e.getMessage());
        }

        // ── Existing Policies ──
        try {
            List<GatewayPolicyEntity> policies = policyService.getAllPolicies();
            sb.append("### Existing Policies (").append(policies.size()).append(" total)\n");
            if (!policies.isEmpty()) {
                sb.append("| Policy Name | Effect | Enabled | Source |\n");
                sb.append("|-------------|--------|---------|--------|\n");
                for (GatewayPolicyEntity p : policies) {
                    sb.append("| ").append(p.getPolicyName()).append(" | ")
                            .append(p.getEffect()).append(" | ")
                            .append(p.getEnabled() ? "Yes" : "No").append(" | ")
                            .append(p.getSource()).append(" |\n");
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            sb.append("### Existing Policies\nUnable to fetch policy data.\n\n");
            log.debug("🤖 Could not fetch policies for LLM metadata: {}", e.getMessage());
        }

        String metadata = sb.toString();
        metadataCache.put("liveMetadata", new CachedValue(metadata, System.currentTimeMillis()));
        return metadata;
    }

    // ════════════════════════════════════════════════════════════════════
    //  TEMPLATE FALLBACK (when no API key)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Generate a Cedar policy from pattern matching on the prompt.
     * This is the demo/fallback mode when no Anthropic API key is configured.
     * Auto-validates generated Cedar before returning.
     */
    private PolicyChatResponse generateFromTemplate(String prompt) {
        String lower = prompt.toLowerCase();

        PolicyChatResponse response = matchTemplate(lower, prompt);

        // Auto-validate template output
        if (response.isSuccess() && response.getPolicyText() != null) {
            String validationError = cedarEngine.validatePolicy(response.getPolicyText());
            if (validationError != null) {
                response.setValidationError(validationError);
                log.warn("🤖 Template generated invalid Cedar: {}", validationError);
            }
            response.setConversationComplete(true);
        }

        return response;
    }

    private PolicyChatResponse matchTemplate(String lower, String prompt) {
        // ── Pattern: "block/deny/forbid" + target ────────────────────
        if (containsAny(lower, "block", "deny", "forbid", "prevent", "restrict")) {

            if (containsAny(lower, "server", "production", "prod")) {
                String serverName = extractQuotedOrLastWord(prompt, "server");
                return buildTemplateResponse(
                        "block-server-" + slugify(serverName),
                        "Block all tool calls to server '" + serverName + "'",
                        "FORBID",
                        String.format("""
                                @id("block-server-%s")
                                forbid(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource is Tool
                                )
                                when {
                                    resource in Server::"%s"
                                };""", slugify(serverName), serverName),
                        "This policy blocks all agents from calling any tool on the '" + serverName + "' server.",
                        prompt
                );
            }

            if (containsAny(lower, "agent")) {
                String agentName = extractQuotedOrLastWord(prompt, "agent");
                return buildTemplateResponse(
                        "block-agent-" + slugify(agentName),
                        "Block agent '" + agentName + "' from all operations",
                        "FORBID",
                        String.format("""
                                @id("block-agent-%s")
                                forbid(
                                    principal == Agent::"%s",
                                    action,
                                    resource
                                );""", slugify(agentName), agentName),
                        "This policy blocks agent '" + agentName + "' from performing any action.",
                        prompt
                );
            }

            if (containsAny(lower, "business hour", "after hour", "outside hour", "night", "weekend")) {
                return buildTemplateResponse(
                        "business-hours-only",
                        "Deny tool calls outside business hours",
                        "FORBID",
                        """
                                @id("business-hours-only")
                                forbid(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource is Tool
                                )
                                unless {
                                    context.businessHours == true
                                };""",
                        "This policy blocks all tool calls outside Mon-Fri 8am-6pm.",
                        prompt
                );
            }

            if (containsAny(lower, "tool")) {
                String toolName = extractQuotedOrLastWord(prompt, "tool");
                return buildTemplateResponse(
                        "block-tool-" + slugify(toolName),
                        "Block specific tool '" + toolName + "'",
                        "FORBID",
                        String.format("""
                                @id("block-tool-%s")
                                forbid(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource == Tool::"%s"
                                );""", slugify(toolName), toolName),
                        "This policy blocks all agents from calling the '" + toolName + "' tool.",
                        prompt
                );
            }

            if (containsAny(lower, "argument", "args", "parameter", "delete", "drop")) {
                String pattern = extractQuotedOrLastWord(prompt, "containing");
                return buildTemplateResponse(
                        "block-args-" + slugify(pattern),
                        "Block tool calls with arguments containing '" + pattern + "'",
                        "FORBID",
                        String.format("""
                                @id("block-args-%s")
                                forbid(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource is Tool
                                )
                                when {
                                    context.argumentsFlat like "*%s*"
                                };""", slugify(pattern), pattern),
                        "This policy blocks any tool call whose arguments contain '" + pattern + "'.",
                        prompt
                );
            }
        }

        // ── Pattern: "allow/permit" + condition ──────────────────────
        if (containsAny(lower, "allow", "permit", "enable", "grant")) {

            if (containsAny(lower, "approved")) {
                if (containsAny(lower, "server") || containsAny(lower, "github", "slack", "jira")) {
                    String serverName = extractQuotedOrLastWord(prompt, "server");
                    return buildTemplateResponse(
                            "approved-agents-" + slugify(serverName) + "-access",
                            "Allow approved agents to call tools on '" + serverName + "' server",
                            "PERMIT",
                            String.format("""
                                    @id("approved-agents-%s-access")
                                    permit(
                                        principal is Agent,
                                        action == Action::"toolCall",
                                        resource is Tool
                                    )
                                    when {
                                        principal in AgentGroup::"APPROVED" &&
                                        resource in Server::"%s"
                                    };""", slugify(serverName), serverName),
                            "This policy allows APPROVED agents to call tools on '" + serverName + "' server only.",
                            prompt
                    );
                }

                return buildTemplateResponse(
                        "approved-agents-tool-access",
                        "Allow approved agents to call tools",
                        "PERMIT",
                        """
                                @id("approved-agents-tool-access")
                                permit(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource is Tool
                                )
                                when {
                                    principal in AgentGroup::"APPROVED"
                                };""",
                        "This policy allows only APPROVED agents to invoke tools.",
                        prompt
                );
            }

            if (containsAny(lower, "agent") && containsAny(lower, "tool")) {
                String agentName = extractQuotedOrLastWord(prompt, "agent");
                String toolName = extractQuotedOrLastWord(prompt, "tool");
                return buildTemplateResponse(
                        "allow-" + slugify(agentName) + "-" + slugify(toolName),
                        "Allow '" + agentName + "' to call '" + toolName + "'",
                        "PERMIT",
                        String.format("""
                                @id("allow-%s-%s")
                                permit(
                                    principal == Agent::"%s",
                                    action == Action::"toolCall",
                                    resource == Tool::"%s"
                                );""", slugify(agentName), slugify(toolName), agentName, toolName),
                        "This policy allows only agent '" + agentName + "' to call tool '" + toolName + "'.",
                        prompt
                );
            }

            if (containsAny(lower, "read", "discovery", "prompt", "resource")) {
                return buildTemplateResponse(
                        "allow-discovery-ops",
                        "Allow all agents to get prompts and read resources",
                        "PERMIT",
                        """
                                @id("allow-discovery-ops")
                                permit(
                                    principal is Agent,
                                    action in [Action::"promptGet", Action::"resourceRead"],
                                    resource
                                );""",
                        "This policy allows all agents to perform read-only discovery operations.",
                        prompt
                );
            }

            if (containsAny(lower, "hour", "time", "morning", "evening")) {
                return buildTemplateResponse(
                        "allow-business-hours",
                        "Allow tool calls only during business hours (8am-6pm weekdays)",
                        "PERMIT",
                        """
                                @id("allow-business-hours")
                                permit(
                                    principal is Agent,
                                    action == Action::"toolCall",
                                    resource is Tool
                                )
                                when {
                                    principal in AgentGroup::"APPROVED" &&
                                    context.hour >= 8 &&
                                    context.hour < 18 &&
                                    context.dayOfWeek != "SATURDAY" &&
                                    context.dayOfWeek != "SUNDAY"
                                };""",
                        "This policy allows APPROVED agents to call tools only Mon-Fri between 8am and 6pm.",
                        prompt
                );
            }
        }

        // ── Default: generic template ───────────────────────────────
        return buildTemplateResponse(
                "custom-policy",
                prompt,
                "PERMIT",
                """
                        @id("custom-policy")
                        permit(
                            principal is Agent,
                            action == Action::"toolCall",
                            resource is Tool
                        )
                        when {
                            principal in AgentGroup::"APPROVED"
                        };""",
                "Generic policy template — please customize the Cedar code for your specific needs. " +
                        "Configure an Anthropic API key for AI-powered policy generation from natural language.",
                prompt
        );
    }

    private PolicyChatResponse buildTemplateResponse(String name, String description,
                                                      String effect, String policyText,
                                                      String explanation, String originalPrompt) {
        return PolicyChatResponse.builder()
                .success(true)
                .policyText(policyText)
                .suggestedName(name)
                .description(description)
                .effect(effect)
                .explanation(explanation)
                .source("TEMPLATE")
                .conversationComplete(true)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    //  PARSING HELPERS
    // ════════════════════════════════════════════════════════════════════

    private String extractCedarBlock(String text) {
        int start = text.indexOf("```cedar");
        if (start == -1) start = text.indexOf("```");
        if (start == -1) return null;

        start = text.indexOf('\n', start) + 1;
        int end = text.indexOf("```", start);
        if (end == -1) return text.substring(start).trim();
        return text.substring(start, end).trim();
    }

    private String extractField(String text, String label) {
        int idx = text.indexOf(label);
        if (idx == -1) return null;
        int start = idx + label.length();
        int end = text.indexOf('\n', start);
        if (end == -1) return text.substring(start).trim();
        return text.substring(start, end).trim();
    }

    private String detectEffect(String policyText) {
        if (policyText != null && policyText.trim().toLowerCase().startsWith("forbid")) return "FORBID";
        return "PERMIT";
    }

    private String generatePolicyName(String prompt) {
        return slugify(prompt.length() > 50 ? prompt.substring(0, 50) : prompt);
    }

    private String slugify(String input) {
        if (input == null) return "unnamed";
        return input.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private String extractQuotedOrLastWord(String prompt, String beforeKeyword) {
        int quoteStart = prompt.indexOf("'");
        if (quoteStart >= 0) {
            int quoteEnd = prompt.indexOf("'", quoteStart + 1);
            if (quoteEnd > quoteStart) {
                return prompt.substring(quoteStart + 1, quoteEnd);
            }
        }
        quoteStart = prompt.indexOf("\"");
        if (quoteStart >= 0) {
            int quoteEnd = prompt.indexOf("\"", quoteStart + 1);
            if (quoteEnd > quoteStart) {
                return prompt.substring(quoteStart + 1, quoteEnd);
            }
        }
        String[] words = prompt.split("\\s+");
        return words.length > 0 ? words[words.length - 1] : "unknown";
    }
}
