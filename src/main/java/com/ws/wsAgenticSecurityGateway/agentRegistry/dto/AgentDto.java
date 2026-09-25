package com.ws.wsAgenticSecurityGateway.agentRegistry.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The canonical agent view for the admin surface (#2/#3): one row per {@code (tenant, name)} identity with its
 * protocols, identity facet, A2A endpoint, and capabilities. Assembled by {@link
 * com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService#getAgentViews()} — the single
 * agent service — from the canonical {@code gateway_agent} row plus the capability registry (exposed skills)
 * and capability profiles (provisioned tools + invokable skills).
 *
 * <p>Field names are a superset of the previous {@code /api/admin/agents} response, so it is a drop-in
 * enrichment of that endpoint — existing consumers keep reading the fields they know.
 */
public record AgentDto(
        UUID id,
        String agentName,
        String agentVersion,
        String protocolVersion,
        String status,
        String approvalStatus,

        /** Which protocols this agent speaks — subset of {@code ["MCP","A2A"]}. */
        List<String> protocols,

        /** Verified workload id (Keycloak client / SPIFFE id later). */
        String workloadId,
        /** The Keycloak client id this agent authenticates as. */
        String authClientId,
        /** How identity is proven — {@code KEYCLOAK}, {@code SPIFFE}, or null (self-asserted). */
        String identitySource,
        /** True when the agent has a proven workload identity (source + workload id present). */
        boolean verified,

        /** A2A endpoint base URL; null for MCP-only agents. */
        String baseUrl,

        /** Tools this agent is PROVISIONED to call (from its capability profile). */
        int toolCount,
        List<String> tools,
        /** Skills this agent EXPOSES (its own, from the capability registry). */
        int skillCount,
        List<String> skills,
        /** Skills this agent may INVOKE on other agents (from its capability profile). */
        List<String> invokableSkills,

        long totalSessions,
        long totalRequests,
        long connectedSessions,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,

        /** MCP initialize handshake payload (populated for the detail view; may be null). */
        JsonNode capabilities
) {
}
