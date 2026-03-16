package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyChatRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyChatResponse;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayCustomAttributeEntity;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li><b>Audit logging</b> — every chat request/response tracked via McpAuditService</li>
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
    private final CustomAttributeService customAttributeService;
    private final McpAuditService auditService;

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
                             CedarPolicyEngine cedarEngine,
                             CustomAttributeService customAttributeService,
                             McpAuditService auditService) {
        this.objectMapper = objectMapper;
        this.agentRegistryService = agentRegistryService;
        this.capabilityRegistryService = capabilityRegistryService;
        this.policyService = policyService;
        this.cedarEngine = cedarEngine;
        this.customAttributeService = customAttributeService;
        this.auditService = auditService;
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

        int messageCount = request.isMultiTurn() ? request.getMessages().size() : 1;
        auditService.auditPdpLlmChatRequested(effectivePrompt, messageCount);

        if (!isLlmAvailable()) {
            log.warn("🤖 LLM not configured — cannot generate policy");
            PolicyChatResponse errorResponse = PolicyChatResponse.error(
                    "LLM policy generation is not configured. Set the Anthropic API key "
                    + "in application.yml (ws.gateway.pdp.anthropic-api-key) to enable "
                    + "AI-powered policy creation.");
            auditService.auditPdpLlmChatCompleted(effectivePrompt, errorResponse.getError(), 0, false);
            return errorResponse;
        }

        long start = System.currentTimeMillis();
        try {
            log.info("🤖 Calling Anthropic API for policy generation: {}", effectivePrompt);
            PolicyChatResponse response = callAnthropicApi(request);
            long durationMs = System.currentTimeMillis() - start;
            String responseText = response.getPolicyText() != null
                    ? response.getPolicyText()
                    : response.getFollowUpQuestion();
            log.info("🤖 Anthropic API completed in {}ms — conversationComplete={}, hasPolicy={}, hasFollowUp={}",
                    durationMs, response.isConversationComplete(),
                    response.getPolicyText() != null, response.getFollowUpQuestion() != null);
            auditService.auditPdpLlmChatCompleted(effectivePrompt, responseText, durationMs, response.isSuccess());
            return response;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("🤖 Anthropic API call failed: {}", e.getMessage(), e);
            auditService.auditPdpLlmChatCompleted(effectivePrompt, e.getMessage(), durationMs, false);
            return PolicyChatResponse.error(
                    "LLM API call failed: " + e.getMessage()
                    + ". Please try again or check the API key configuration.");
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

                // Check for semantic warnings (valid syntax but likely incorrect intent)
                String semanticWarning = cedarEngine.getSemanticWarnings(policyText);
                if (semanticWarning != null) {
                    log.warn("🤖 LLM generated Cedar with semantic warning: {}", semanticWarning);
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
                        .semanticWarning(semanticWarning)
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
                - `permit` — allow access (REQUIRED to grant access — without a matching permit, default-deny blocks the request)
                - `forbid` — deny access (overrides permit — use to carve out exceptions from a broader permit)

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
                3. **DEFAULT-DENY**: If no `permit` policy matches a request, it is DENIED. To ALLOW something, you MUST write a `permit` policy. A `forbid-unless` does NOT grant access — it only exempts from denial.
                4. `forbid` always overrides `permit` (explicit deny wins)
                5. Use `when` for positive conditions and `unless` for exceptions within a single policy
                6. Keep policies focused — one concern per policy
                7. Use exact entity names from the Live System Metadata section below when available
                8. If the admin's request is ambiguous, ask a follow-up question instead of guessing
                9. Always validate that referenced agents, servers, and tools exist in the live metadata
                10. **CRITICAL — NEVER use `forbid-unless` as the sole policy to grant access.** The `unless` clause only exempts a request from being forbidden — it does NOT create a permit. In a default-deny system, an exempted request with no matching permit is still DENIED.
                11. When the admin says "only allow X" or "restrict to X", always use a `permit` for X. Do NOT write `forbid everything unless X`. Default-deny already blocks everything else.

                ## Pattern Catalog (use these — do NOT invent alternatives)

                ### "Only allow specific tools for an agent"
                Admin: "Restrict claude-ai to only use Github_get_me"
                CORRECT — use permit:
                ```cedar
                @id("claude-get-me-only")
                permit(
                    principal == Agent::"claude-ai",
                    action == Action::"toolCall",
                    resource == Tool::"Github_get_me"
                );
                ```
                Why: `permit` grants access. Default-deny blocks all other tools automatically.

                WRONG — do NOT use forbid-unless:
                ```cedar
                forbid(principal == Agent::"claude-ai", action == Action::"toolCall", resource is Tool)
                unless { resource == Tool::"Github_get_me" };
                ```
                Why wrong: The `unless` exempts Github_get_me from the forbid, but no `permit` exists, so default-deny still blocks it.

                ### "Allow everything except specific tools"
                Admin: "Allow agent data-bot all tools except Github_delete_file"
                CORRECT — permit broad + forbid specific:
                ```cedar
                @id("data-bot-allow-tools")
                permit(
                    principal == Agent::"data-bot",
                    action == Action::"toolCall",
                    resource is Tool
                );

                @id("data-bot-block-delete")
                forbid(
                    principal == Agent::"data-bot",
                    action == Action::"toolCall",
                    resource == Tool::"Github_delete_file"
                );
                ```

                ### "Business hours only"
                Admin: "Only allow tool calls during business hours"
                CORRECT — permit with when:
                ```cedar
                @id("business-hours-only")
                permit(
                    principal is Agent,
                    action == Action::"toolCall",
                    resource is Tool
                )
                when {
                    context.businessHours == true
                };
                ```
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

        // ── Custom Attributes (admin-registered for ABAC) ──
        try {
            List<GatewayCustomAttributeEntity> customAttrs = customAttributeService.getEnabledAttributes();
            if (customAttrs != null && !customAttrs.isEmpty()) {
                sb.append("### Custom Attributes (admin-registered, available as context.<name>)\n");
                sb.append("| Attribute Name | Data Type | Value Source | Source Key | Default |\n");
                sb.append("|----------------|-----------|-------------|------------|--------|\n");
                for (GatewayCustomAttributeEntity attr : customAttrs) {
                    sb.append("| `").append(attr.getAttributeName()).append("` | ")
                            .append(attr.getDataType()).append(" | ")
                            .append(attr.getValueSource()).append(" | ")
                            .append(attr.getSourceKey() != null ? attr.getSourceKey() : "-").append(" | ")
                            .append(attr.getDefaultValue() != null ? attr.getDefaultValue() : "-").append(" |\n");
                }
                sb.append("\nUse these in policies as `context.").append(customAttrs.get(0).getAttributeName())
                        .append("` with the appropriate operator for their data type.\n\n");
            }
        } catch (Exception e) {
            log.debug("🤖 Could not fetch custom attributes for LLM metadata: {}", e.getMessage());
        }

        String metadata = sb.toString();
        metadataCache.put("liveMetadata", new CachedValue(metadata, System.currentTimeMillis()));
        return metadata;
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
        String base = prompt.length() > 50 ? prompt.substring(0, 50) : prompt;
        if (base == null) return "unnamed";
        return base.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
