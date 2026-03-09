package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyChatResponse;
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

/**
 * LLM-powered Cedar policy generation service.
 *
 * <p>Converts natural-language admin prompts into Cedar policy code using
 * the Anthropic Claude API (Messages endpoint).
 *
 * <h3>Migration Path</h3>
 * <ul>
 *   <li><b>No API key</b> → returns demo templates (hardcoded patterns)</li>
 *   <li><b>With API key</b> → calls Anthropic API for real LLM generation</li>
 * </ul>
 *
 * <p>Admin just sets {@code ws.gateway.pdp.anthropic-api-key} in application.yml
 * to switch from demo mode to full LLM generation. Zero code changes needed.
 */
@Service
@Slf4j
public class PolicyLlmService {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-20250514";
    private static final int MAX_TOKENS = 2048;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${ws.gateway.pdp.anthropic-api-key:}")
    private String anthropicApiKey;

    public PolicyLlmService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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

    /**
     * Generate a Cedar policy from a natural-language admin prompt.
     *
     * <p>If no API key is configured, falls back to template matching.
     * This ensures the demo works without API access.
     *
     * @param prompt the admin's natural-language policy description
     * @return generated policy response with Cedar code
     */
    public PolicyChatResponse generatePolicy(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return PolicyChatResponse.error("Prompt cannot be empty");
        }

        if (!isLlmAvailable()) {
            log.info("🤖 LLM not configured — using template fallback for: {}", prompt);
            return generateFromTemplate(prompt);
        }

        try {
            log.info("🤖 Calling Anthropic API for policy generation: {}", prompt);
            return callAnthropicApi(prompt);
        } catch (Exception e) {
            log.error("🤖 Anthropic API call failed: {} — falling back to template", e.getMessage());
            return generateFromTemplate(prompt);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  ANTHROPIC API INTEGRATION
    // ════════════════════════════════════════════════════════════════════

    private PolicyChatResponse callAnthropicApi(String prompt) throws Exception {
        String systemPrompt = buildSystemPrompt();

        Map<String, Object> requestBody = Map.of(
                "model", MODEL,
                "max_tokens", MAX_TOKENS,
                "system", systemPrompt,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                )
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

        return parseAnthropicResponse(response.body(), prompt);
    }

    private PolicyChatResponse parseAnthropicResponse(String responseBody, String originalPrompt) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("content");
            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText("");

                // Extract Cedar policy from the response
                // LLM is instructed to wrap policy in ```cedar ... ``` blocks
                String policyText = extractCedarBlock(text);
                String policyName = extractField(text, "POLICY_NAME:");
                String description = extractField(text, "DESCRIPTION:");
                String effect = extractField(text, "EFFECT:");
                String explanation = extractField(text, "EXPLANATION:");

                if (policyText == null || policyText.isBlank()) {
                    // Fallback: treat entire response as policy text
                    policyText = text.trim();
                }

                return PolicyChatResponse.builder()
                        .success(true)
                        .policyText(policyText)
                        .suggestedName(policyName != null ? policyName : generatePolicyName(originalPrompt))
                        .description(description != null ? description : originalPrompt)
                        .effect(effect != null ? effect : detectEffect(policyText))
                        .explanation(explanation)
                        .source("LLM_GENERATED")
                        .build();
            }

            return PolicyChatResponse.error("Empty response from Anthropic API");

        } catch (Exception e) {
            log.error("🤖 Failed to parse Anthropic response: {}", e.getMessage());
            return PolicyChatResponse.error("Failed to parse LLM response: " + e.getMessage());
        }
    }

    /**
     * System prompt that teaches the LLM our Cedar entity model.
     * This is the key to reliable policy generation.
     */
    private String buildSystemPrompt() {
        return """
                You are a policy generation assistant for the WS Agentic Security Gateway.
                You generate Cedar authorization policies based on natural-language admin descriptions.

                ## Cedar Entity Model

                ### Principal Types
                - `Agent::"<agent_name>"` — AI agent identified by name (e.g., Agent::"claude-desktop")
                - `AgentGroup::"APPROVED"` — Agents with APPROVED approval status
                - `AgentGroup::"PENDING"` — Agents with PENDING approval status
                - `AgentGroup::"BLOCKED"` — Agents that have been blocked

                ### Action Types
                - `Action::"toolCall"` — Invoke a tool on an MCP server
                - `Action::"promptGet"` — Get a prompt from an MCP server
                - `Action::"resourceRead"` — Read a resource from an MCP server

                ### Resource Types
                - `Tool::"<public_name>"` — A tool with namespaced name (e.g., Tool::"github_create_issue")
                - `Prompt::"<public_name>"` — A prompt
                - `Resource::"<public_name>"` — A resource
                - `Server::"<server_name>"` — An MCP server (parent of tools/prompts/resources)

                ### Resource Hierarchy
                - Tools/Prompts/Resources belong to their Server via `in` operator
                - Example: `Tool::"github_create_issue" in Server::"github"`

                ### Agent Attributes (usable in when/unless blocks)
                - `principal.version` — Agent software version (string)
                - `principal.approvalStatus` — "APPROVED", "PENDING", or "BLOCKED" (string)

                ### Resource Attributes (usable in when/unless blocks)
                - `resource.originalName` — Tool's original name on the server (string)
                - `resource.serverName` — Server name (string)

                ### Context (environment/request data)
                - `context.hour` — Current hour (0-23, long)
                - `context.dayOfWeek` — Day name: "MONDAY", "TUESDAY", etc. (string)
                - `context.businessHours` — true if Mon-Fri 8am-6pm (boolean)
                - `context.sourceIp` — Agent's IP address (string)
                - `context.serverName` — Target server name (string)
                - `context.resourceName` — Tool/prompt/resource name (string)
                - `context.correlationId` — Request trace ID (string)
                - `context.argumentsFlat` — Flattened tool arguments for pattern matching (string)

                ## Supported Cedar Syntax

                ### Head Clause (inside permit/forbid parentheses)
                - `principal is Agent` — match any agent (type constraint)
                - `principal == Agent::"claude-desktop"` — match specific agent (entity equality)
                - `action == Action::"toolCall"` — match specific action
                - `action in [Action::"toolCall", Action::"promptGet"]` — match action set
                - `action` — match any action (no constraint)
                - `resource is Tool` — match any tool (type constraint)
                - `resource == Tool::"github_create_issue"` — match specific tool (entity equality)
                - `resource` — match any resource (no constraint)

                ### Operators in when/unless Blocks
                - `==` equality: `context.dayOfWeek == "MONDAY"`, `context.hour == 9`
                - `!=` inequality: `context.dayOfWeek != "SATURDAY"`, `context.businessHours != false`
                - `>`, `<`, `>=`, `<=` numeric comparison: `context.hour >= 8`, `context.hour < 18`
                - `like` wildcard matching: `context.argumentsFlat like "*production*"` (uses * as wildcard)
                - `.contains()` substring check: `context.argumentsFlat.contains("secret")`
                - `in` hierarchy membership: `principal in AgentGroup::"APPROVED"`, `resource in Server::"github"`
                - `&&` combine multiple conditions: condition1 && condition2 && condition3

                ### Attribute Access in when/unless Blocks
                - `principal.version == "1.0"` — check agent version
                - `principal.approvalStatus == "APPROVED"` — check approval status
                - `resource.originalName == "create_issue"` — check original tool name
                - `resource.serverName == "github"` — check parent server

                ## Response Format

                Always respond with:

                POLICY_NAME: <short-kebab-case-name>
                DESCRIPTION: <one-sentence description>
                EFFECT: <PERMIT or FORBID>
                EXPLANATION: <brief explanation of what this policy does>

                ```cedar
                <the cedar policy code>
                ```

                ## Rules
                1. Every policy MUST have an @id annotation matching the POLICY_NAME
                2. Use `permit` for allow policies and `forbid` for deny policies
                3. Cedar is default-deny — if no permit matches, the request is denied
                4. `forbid` policies always override `permit` policies
                5. Use `when` for positive conditions and `unless` for exceptions
                6. Keep policies simple and focused — one concern per policy
                7. Use `principal is Agent` for broad policies; use `principal == Agent::"X"` to target a specific agent
                8. Use `resource is Tool` for all tools; use `resource == Tool::"X"` to target a specific tool
                9. Combine conditions with `&&` for granular control (e.g., agent + server + time)
                10. Use `context.argumentsFlat like "*pattern*"` to inspect tool call arguments
                """;
    }

    // ════════════════════════════════════════════════════════════════════
    //  TEMPLATE FALLBACK (when no API key)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Generate a Cedar policy from pattern matching on the prompt.
     * This is the demo/fallback mode when no Anthropic API key is configured.
     */
    private PolicyChatResponse generateFromTemplate(String prompt) {
        String lower = prompt.toLowerCase();

        // ── Pattern: "block/deny/forbid" + agent/server name ──────────
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

            // ── Pattern: block specific tool ────────────────────────
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

            // ── Pattern: block arguments containing pattern ─────────
            if (containsAny(lower, "argument", "args", "parameter", "production", "delete", "drop")) {
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

        // ── Pattern: "allow/permit" + some condition ──────────────────
        if (containsAny(lower, "allow", "permit", "enable", "grant")) {

            if (containsAny(lower, "approved")) {
                // Check for combined agent+server condition
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

            // ── Pattern: allow specific agent + specific tool ───────
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

            // ── Pattern: allow during specific hours ────────────────
            if (containsAny(lower, "hour", "time", "morning", "evening", "night")) {
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

        // ── Default: generic tool access policy ───────────────────────
        return buildTemplateResponse(
                "custom-policy",
                prompt,
                "PERMIT",
                String.format("""
                        @id("custom-policy")
                        permit(
                            principal is Agent,
                            action == Action::"toolCall",
                            resource is Tool
                        )
                        when {
                            principal in AgentGroup::"APPROVED"
                        };"""),
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
                .source(isLlmAvailable() ? "LLM_GENERATED" : "TEMPLATE")
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
        // Try to find a quoted name
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
        // Fallback: last meaningful word
        String[] words = prompt.split("\\s+");
        return words.length > 0 ? words[words.length - 1] : "unknown";
    }
}
