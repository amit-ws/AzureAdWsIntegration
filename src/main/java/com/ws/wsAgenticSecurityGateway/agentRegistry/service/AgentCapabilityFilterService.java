package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfile;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfileAssignment;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfileRule;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileAssignmentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileRepository;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor.CapabilityType;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent Capability Filtering Service — enforces per-agent capability access
 * using profile-based rules (default deny).
 *
 * <p>Maintains an in-memory cache of pre-computed allowed capability sets
 * per agent for O(1) lookups on the orchestration hot path.
 *
 * <p><strong>Default deny</strong>: agents with no assigned profiles have
 * an empty allowed set — they cannot see or call any capabilities.
 */
@Service
@Slf4j
public class AgentCapabilityFilterService {

    private final AgentCapabilityProfileRepository profileRepository;
    private final AgentCapabilityProfileAssignmentRepository assignmentRepository;
    private final CapabilityRegistryService registryService;
    private final AgentRegistryService agentRegistryService;

    /**
     * Pre-computed allowed capabilities per agent, keyed by capability type.
     * agentId -> { "TOOL" -> Set(publicName), "PROMPT" -> Set(publicName), "RESOURCE" -> Set(publicName) }
     */
    private final ConcurrentHashMap<UUID, Map<String, Set<String>>> agentAllowedCapabilities =
            new ConcurrentHashMap<>();

    public AgentCapabilityFilterService(AgentCapabilityProfileRepository profileRepository,
                                         AgentCapabilityProfileAssignmentRepository assignmentRepository,
                                         CapabilityRegistryService registryService,
                                         AgentRegistryService agentRegistryService) {
        this.profileRepository = profileRepository;
        this.assignmentRepository = assignmentRepository;
        this.registryService = registryService;
        this.agentRegistryService = agentRegistryService;
    }

    // ════════════════════════════════════════════════════════════════════
    // STARTUP — warm cache from database
    // ════════════════════════════════════════════════════════════════════

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warmCache() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("🔐 CAPABILITY FILTER — Warming cache from database");
        log.info("═══════════════════════════════════════════════════════════");

        List<AgentCapabilityProfileAssignment> allAssignments = assignmentRepository.findAll();

        // Group by agentId, compute allowed set for each
        Map<UUID, List<AgentCapabilityProfileAssignment>> byAgent = allAssignments.stream()
                .collect(Collectors.groupingBy(AgentCapabilityProfileAssignment::getAgentId));

        for (Map.Entry<UUID, List<AgentCapabilityProfileAssignment>> entry : byAgent.entrySet()) {
            UUID agentId = entry.getKey();
            List<UUID> profileIds = entry.getValue().stream()
                    .map(AgentCapabilityProfileAssignment::getProfileId)
                    .collect(Collectors.toList());
            Map<String, Set<String>> allowed = computeAllowedCapabilities(profileIds);
            agentAllowedCapabilities.put(agentId, allowed);
        }

        log.info("   Loaded capability filters for {} agent(s)", byAgent.size());
        for (Map.Entry<UUID, Map<String, Set<String>>> entry : agentAllowedCapabilities.entrySet()) {
            Map<String, Set<String>> byType = entry.getValue();
            int tools = byType.getOrDefault("TOOL", Set.of()).size();
            int prompts = byType.getOrDefault("PROMPT", Set.of()).size();
            int resources = byType.getOrDefault("RESOURCE", Set.of()).size();
            log.info("   - Agent {}: {} tools, {} prompts, {} resources",
                    entry.getKey(), tools, prompts, resources);
        }

        log.info("═══════════════════════════════════════════════════════════");
    }

    // ════════════════════════════════════════════════════════════════════
    // HOT PATH — O(1) checks for orchestrator + list filtering
    // ════════════════════════════════════════════════════════════════════

    /**
     * Check if a specific capability is allowed for this agent.
     *
     * <p><strong>Default deny</strong>: if the agent has no profiles in the cache,
     * returns false (no access).
     *
     * @param agentId    the agent's UUID
     * @param publicName the namespaced capability name (e.g., "github_create_issue")
     * @param type       capability type: "TOOL", "PROMPT", or "RESOURCE"
     * @return true if the capability is in the agent's allowed set
     */
    public boolean isCapabilityAllowed(UUID agentId, String publicName, String type) {
        Map<String, Set<String>> byType = agentAllowedCapabilities.get(agentId);
        if (byType == null) {
            return false; // default deny — no profiles assigned
        }
        Set<String> allowed = byType.get(type);
        return allowed != null && allowed.contains(publicName);
    }

    /**
     * Get the set of allowed capability publicNames for list filtering.
     *
     * @param agentId the agent's UUID
     * @param type    capability type: "TOOL", "PROMPT", or "RESOURCE"
     * @return unmodifiable set of allowed publicNames (empty if no profiles)
     */
    public Set<String> getAllowedCapabilities(UUID agentId, String type) {
        Map<String, Set<String>> byType = agentAllowedCapabilities.get(agentId);
        if (byType == null) {
            return Set.of(); // default deny
        }
        return byType.getOrDefault(type, Set.of());
    }

    /**
     * Resolve session ID to agent ID for the orchestrator hot path.
     */
    public UUID resolveAgentId(String sessionId) {
        return agentRegistryService.getAgentIdForSession(sessionId);
    }

    /**
     * Check whether an agent has any profiles assigned.
     * Used by list response filtering to decide whether to filter at all.
     */
    public boolean hasProfiles(UUID agentId) {
        return agentAllowedCapabilities.containsKey(agentId);
    }

    // ════════════════════════════════════════════════════════════════════
    // CACHE MANAGEMENT — called on profile CRUD operations
    // ════════════════════════════════════════════════════════════════════

    /**
     * Recompute and update the allowed capability set for a specific agent.
     * Called after profile assignment/unassignment or profile rule changes.
     */
    @Transactional(readOnly = true)
    public void recomputeAgentAccess(UUID agentId) {
        List<AgentCapabilityProfileAssignment> assignments = assignmentRepository.findByAgentId(agentId);

        if (assignments.isEmpty()) {
            agentAllowedCapabilities.remove(agentId);
            log.info("🔐 Agent {} removed from capability filter cache (no profiles)", agentId);
            return;
        }

        List<UUID> profileIds = assignments.stream()
                .map(AgentCapabilityProfileAssignment::getProfileId)
                .collect(Collectors.toList());
        Map<String, Set<String>> allowed = computeAllowedCapabilities(profileIds);
        agentAllowedCapabilities.put(agentId, allowed);

        int tools = allowed.getOrDefault("TOOL", Set.of()).size();
        int prompts = allowed.getOrDefault("PROMPT", Set.of()).size();
        int resources = allowed.getOrDefault("RESOURCE", Set.of()).size();
        log.info("🔐 Recomputed access for agent {}: {} tools, {} prompts, {} resources",
                agentId, tools, prompts, resources);
    }

    /**
     * Recompute cache for ALL agents that have a specific profile assigned.
     * Called after profile rules are updated or profile is deleted.
     */
    @Transactional(readOnly = true)
    public void recomputeForProfile(UUID profileId) {
        List<AgentCapabilityProfileAssignment> assignments = assignmentRepository.findByProfileId(profileId);
        for (AgentCapabilityProfileAssignment assignment : assignments) {
            recomputeAgentAccess(assignment.getAgentId());
        }
    }

    /**
     * Get all profiles (for REST API and chat assistant context).
     */
    public List<AgentCapabilityProfile> getAllProfiles() {
        return profileRepository.findAll();
    }

    /**
     * Get template profiles.
     */
    public List<AgentCapabilityProfile> getTemplateProfiles() {
        return profileRepository.findByIsTemplateTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // INTERNAL — compute allowed capabilities from profiles
    // ════════════════════════════════════════════════════════════════════

    /**
     * Compute the union of all allowed capabilities across multiple profiles.
     *
     * <p>For each profile's rules:
     * <ul>
     *   <li>INCLUDE_ALL: add all capabilities of the matching type from the server</li>
     *   <li>INCLUDE_ONLY: add only the named capabilities from the server</li>
     *   <li>EXCLUDE: add all capabilities minus the named ones from the server</li>
     * </ul>
     *
     * <p>Union semantics: a capability is allowed if ANY profile grants it.
     */
    private Map<String, Set<String>> computeAllowedCapabilities(List<UUID> profileIds) {
        Set<String> allowedTools = new HashSet<>();
        Set<String> allowedPrompts = new HashSet<>();
        Set<String> allowedResources = new HashSet<>();

        for (UUID profileId : profileIds) {
            Optional<AgentCapabilityProfile> optProfile = profileRepository.findById(profileId);
            if (optProfile.isEmpty()) continue;

            AgentCapabilityProfile profile = optProfile.get();
            for (AgentCapabilityProfileRule rule : profile.getRules()) {
                String server = rule.getServerConfigName();
                String type = rule.getCapabilityType();
                String mode = rule.getMode();

                // Get all capabilities from this server
                List<CapabilityDescriptor> serverCaps = registryService.getCapabilitiesByServer(server);

                // Filter by capability type if not ALL
                List<CapabilityDescriptor> filtered;
                if ("ALL".equals(type)) {
                    filtered = serverCaps;
                } else {
                    CapabilityType capType = CapabilityType.valueOf(type);
                    filtered = serverCaps.stream()
                            .filter(d -> d.getType() == capType)
                            .collect(Collectors.toList());
                }

                // Parse named capabilities (for INCLUDE_ONLY / EXCLUDE)
                Set<String> namedOriginals = parseCapabilityNames(rule.getCapabilityNames());

                // Apply mode
                Set<String> resolvedNames;
                switch (mode) {
                    case "INCLUDE_ALL":
                        resolvedNames = filtered.stream()
                                .map(CapabilityDescriptor::getPublicName)
                                .collect(Collectors.toSet());
                        break;
                    case "INCLUDE_ONLY":
                        resolvedNames = filtered.stream()
                                .filter(d -> namedOriginals.contains(d.getOriginalName()))
                                .map(CapabilityDescriptor::getPublicName)
                                .collect(Collectors.toSet());
                        break;
                    case "EXCLUDE":
                        resolvedNames = filtered.stream()
                                .filter(d -> !namedOriginals.contains(d.getOriginalName()))
                                .map(CapabilityDescriptor::getPublicName)
                                .collect(Collectors.toSet());
                        break;
                    default:
                        log.warn("Unknown rule mode '{}' in profile rule {}", mode, rule.getId());
                        resolvedNames = Set.of();
                }

                // Add to appropriate type sets (union semantics)
                for (String publicName : resolvedNames) {
                    Optional<CapabilityDescriptor> desc = registryService.lookupByPublicName(publicName);
                    if (desc.isEmpty()) continue;
                    switch (desc.get().getType()) {
                        case TOOL -> allowedTools.add(publicName);
                        case PROMPT -> allowedPrompts.add(publicName);
                        case RESOURCE -> allowedResources.add(publicName);
                    }
                }
            }
        }

        Map<String, Set<String>> result = new HashMap<>();
        result.put("TOOL", Collections.unmodifiableSet(allowedTools));
        result.put("PROMPT", Collections.unmodifiableSet(allowedPrompts));
        result.put("RESOURCE", Collections.unmodifiableSet(allowedResources));
        return result;
    }

    /**
     * Parse comma-separated capability names from a rule.
     */
    private Set<String> parseCapabilityNames(String capabilityNames) {
        if (capabilityNames == null || capabilityNames.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(capabilityNames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
