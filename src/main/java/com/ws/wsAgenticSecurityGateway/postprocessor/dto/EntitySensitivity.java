package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import java.util.List;
import java.util.Map;

/**
 * Per-entity sensitivity rollup for badging the existing Agents / Servers pages — each value is the entity's
 * PEAK sensitivity (standard label; the UI maps to None/Low/Medium/High). Keyed by exact ids so the pages join
 * deterministically.
 *
 * @param agents        agentId → peak (an agent as producer OR consumer)
 * @param servers       serverId → peak (a server's tools/prompts/resources)
 * @param serversByName serverName → peak (convenience for pages that key servers by config name)
 * @param tools         per tool/prompt/resource peak
 * @param skills        per skill peak (keyed by producing agent id + skill name)
 * @param humans        username → peak (the human ROOT who triggered the hop; badges the Human Users page)
 * @param nhis          nhiId → peak (the NHI/service-account ROOT of an automated hop; badges the NHIs page)
 */
public record EntitySensitivity(
        Map<String, String> agents,
        Map<String, String> servers,
        Map<String, String> serversByName,
        List<ToolSensitivity> tools,
        List<SkillSensitivity> skills,
        Map<String, String> humans,
        Map<String, String> nhis) {
}
