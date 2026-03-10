package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfile;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM-powered capability profile generation service.
 *
 * <p>Converts natural-language admin requests into structured capability profile
 * definitions using the Anthropic Claude API.
 *
 * <p>Example: "Create a profile for OCR agents with read-only github access and
 * slack messaging" → returns structured JSON with profile name, description, and rules.
 */
@Service
@Slf4j
public class CapabilityProfileChatService {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-20250514";
    private static final int MAX_TOKENS = 2048;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AgentRegistryService agentRegistryService;
    private final AgentCapabilityFilterService filterService;
    private final CapabilityRegistryService registryService;

    @Value("${ws.gateway.pdp.anthropic-api-key:}")
    private String anthropicApiKey;

    public CapabilityProfileChatService(ObjectMapper objectMapper,
                                          AgentRegistryService agentRegistryService,
                                          AgentCapabilityFilterService filterService,
                                          CapabilityRegistryService registryService) {
        this.objectMapper = objectMapper;
        this.agentRegistryService = agentRegistryService;
        this.filterService = filterService;
        this.registryService = registryService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isLlmAvailable() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }

    /**
     * Generate a capability profile definition from a natural-language prompt.
     *
     * @param prompt admin's natural-language request
     * @param conversationHistory optional previous messages for multi-turn
     * @return structured profile definition or error/follow-up
     */
    public Map<String, Object> generateProfile(String prompt, List<Map<String, String>> conversationHistory) {
        if (prompt == null || prompt.isBlank()) {
            return Map.of("error", "Prompt cannot be empty");
        }

        if (!isLlmAvailable()) {
            return Map.of("error", "LLM not configured. Set ws.gateway.pdp.anthropic-api-key in application.yml.");
        }

        long start = System.currentTimeMillis();
        try {
            String systemPrompt = buildSystemPrompt();

            List<Map<String, String>> messages;
            if (conversationHistory != null && !conversationHistory.isEmpty()) {
                messages = new ArrayList<>(conversationHistory);
                messages.add(Map.of("role", "user", "content", prompt));
            } else {
                messages = List.of(Map.of("role", "user", "content", prompt));
            }

            Map<String, Object> requestBody = Map.of(
                    "model", MODEL,
                    "max_tokens", MAX_TOKENS,
                    "system", systemPrompt,
                    "messages", messages);

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
            long durationMs = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                log.error("Anthropic API error ({}): {}", response.statusCode(), response.body());
                return Map.of("error", "LLM API returned status " + response.statusCode());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            String content = responseJson.path("content").get(0).path("text").asText("");

            log.info("Profile chat completed in {}ms", durationMs);

            // Try to parse structured JSON from the response
            return parseProfileResponse(content);

        } catch (Exception e) {
            log.error("Profile chat failed: {}", e.getMessage(), e);
            return Map.of("error", "LLM call failed: " + e.getMessage());
        }
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a capability access profile assistant for the WS MCP Gateway.\n\n");
        sb.append("Your job is to help admins create Capability Access Profiles that control ");
        sb.append("which MCP capabilities (tools, prompts, resources) agents can see and use.\n\n");

        sb.append("## Profile Structure\n");
        sb.append("A profile has: name, description, and one or more rules.\n");
        sb.append("Each rule targets a specific MCP server and has:\n");
        sb.append("- serverConfigName: the MCP server name\n");
        sb.append("- capabilityType: ALL, TOOL, PROMPT, or RESOURCE\n");
        sb.append("- mode: INCLUDE_ALL (all from this server), INCLUDE_ONLY (only named), EXCLUDE (all except named)\n");
        sb.append("- capabilityNames: comma-separated original names (for INCLUDE_ONLY/EXCLUDE)\n\n");

        sb.append("## Available Servers and Capabilities\n");
        Collection<CapabilityDescriptor> allCaps = registryService.getAllCapabilities();
        Map<String, List<CapabilityDescriptor>> byServer = allCaps.stream()
                .collect(Collectors.groupingBy(CapabilityDescriptor::getServerConfigName));

        for (Map.Entry<String, List<CapabilityDescriptor>> entry : byServer.entrySet()) {
            sb.append("### Server: ").append(entry.getKey()).append("\n");
            Map<String, List<String>> byType = entry.getValue().stream()
                    .collect(Collectors.groupingBy(
                            d -> d.getType().name(),
                            Collectors.mapping(CapabilityDescriptor::getOriginalName, Collectors.toList())));
            for (Map.Entry<String, List<String>> typeEntry : byType.entrySet()) {
                sb.append("  ").append(typeEntry.getKey()).append("s: ")
                        .append(String.join(", ", typeEntry.getValue())).append("\n");
            }
        }

        sb.append("\n## Existing Profiles\n");
        List<AgentCapabilityProfile> profiles = filterService.getAllProfiles();
        if (profiles.isEmpty()) {
            sb.append("No profiles exist yet.\n");
        } else {
            for (AgentCapabilityProfile profile : profiles) {
                sb.append("- ").append(profile.getName());
                if (profile.getDescription() != null) {
                    sb.append(": ").append(profile.getDescription());
                }
                sb.append("\n");
            }
        }

        sb.append("\n## Registered Agents\n");
        sb.append("These are the agents in the system. If the admin mentions an agent by name, ");
        sb.append("include it in assignToAgents so the profile gets auto-assigned.\n");
        List<GatewayAgentEntity> agents = agentRegistryService.getAllAgents();
        for (GatewayAgentEntity agent : agents) {
            sb.append("- ").append(agent.getAgentName());
            if (agent.getAgentVersion() != null) {
                sb.append(" v").append(agent.getAgentVersion());
            }
            sb.append(" (").append(agent.getApprovalStatus()).append(")\n");
        }

        sb.append("\n## Response Format\n");
        sb.append("Always respond with a JSON object wrapped in ```json ... ``` containing:\n");
        sb.append("{\n");
        sb.append("  \"name\": \"profile name\",\n");
        sb.append("  \"description\": \"what this profile grants\",\n");
        sb.append("  \"rules\": [\n");
        sb.append("    {\n");
        sb.append("      \"serverConfigName\": \"server-name\",\n");
        sb.append("      \"capabilityType\": \"ALL|TOOL|PROMPT|RESOURCE\",\n");
        sb.append("      \"mode\": \"INCLUDE_ALL|INCLUDE_ONLY|EXCLUDE\",\n");
        sb.append("      \"capabilityNames\": [\"name1\", \"name2\"]  // null for INCLUDE_ALL\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"assignToAgents\": [\"agent-name-1\"],  // optional: agent names to auto-assign this profile to\n");
        sb.append("  \"message\": \"Human-readable explanation of what this profile does\"\n");
        sb.append("}\n\n");
        sb.append("If the admin mentions specific agent names (e.g., 'for OCR-Agent' or 'assign to Slack-Bot'), ");
        sb.append("include those exact agent names in the assignToAgents array. Match against the agent list above.\n");
        sb.append("If no agents are mentioned, omit assignToAgents or set it to an empty array.\n\n");
        sb.append("If the request is unclear, ask a follow-up question (no JSON needed).\n");
        sb.append("Use INCLUDE_ALL when the admin says 'all' or 'full access' for a server.\n");
        sb.append("Use INCLUDE_ONLY when specific capabilities are named.\n");
        sb.append("Use EXCLUDE when the admin says 'all except' or 'everything but'.\n");
        sb.append("For 'read-only' access, use INCLUDE_ONLY with tools containing: list, get, search, read, describe, view.\n");

        return sb.toString();
    }

    private Map<String, Object> parseProfileResponse(String content) {
        // Try to extract JSON from ```json ... ``` block
        int jsonStart = content.indexOf("```json");
        int jsonEnd = content.lastIndexOf("```");

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            String jsonStr = content.substring(jsonStart + 7, jsonEnd).trim();
            try {
                JsonNode json = objectMapper.readTree(jsonStr);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("profile", objectMapper.convertValue(json, Map.class));
                result.put("rawResponse", content);
                return result;
            } catch (Exception e) {
                log.debug("Failed to parse JSON from LLM response: {}", e.getMessage());
            }
        }

        // No JSON found — treat as a follow-up question or plain text response
        return Map.of(
                "success", false,
                "followUp", true,
                "message", content);
    }
}
