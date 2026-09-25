package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfile;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfileAssignment;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfileRule;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileAssignmentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileRepository;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.event.CapabilityRegistryChangedEvent;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor.CapabilityType;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentCapabilityFilterService {

    private final AgentCapabilityProfileRepository profileRepository;
    private final AgentCapabilityProfileAssignmentRepository assignmentRepository;
    private final CapabilityRegistryService registryService;
    private final AgentRegistryService agentRegistryService;

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

    // @Order(100) runs this AFTER AgentSourceReconciler (@Order(10)) has registered downstream A2A skills into
    // the capability registry, so SKILL-exposure profiles resolve against a populated registry rather than
    // warming empty and fail-closing every A2A skill hop. MCP tools register at @PostConstruct (earlier phase),
    // so TOOL profiles are already safe regardless of this ordering.
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    @Transactional(readOnly = true)
    public void warmCache() {
        int n = reloadAllAgents();
        log.info("CAPABILITY FILTER — warmed capability filters for {} agent(s)", n);
    }

    /**
     * The allow-sets resolve against the LIVE capability registry, so they go stale the moment that registry
     * changes — a server connecting/reconnecting (its tools (re)appear) or disconnecting/disabling (its tools
     * vanish). Recompute on the registry-changed event so the cache tracks reality. Without this the cache keeps
     * whatever it held at warm time: EMPTY if the MCP server was down at startup and connected later, or stale
     * after a disable/re-enable — silently locking agents out of tools they are actually granted.
     */
    @EventListener(CapabilityRegistryChangedEvent.class)
    @Transactional(readOnly = true)
    public void onCapabilityRegistryChanged(CapabilityRegistryChangedEvent event) {
        int n = reloadAllAgents();
        log.debug("Recomputed capability filters for {} agent(s) after registry change", n);
    }

    /** Rebuild every agent's allow-sets from its assignments against the current registry. Returns agent count. */
    private int reloadAllAgents() {
        List<AgentCapabilityProfileAssignment> allAssignments = assignmentRepository.findAll();
        Map<UUID, List<AgentCapabilityProfileAssignment>> byAgent = allAssignments.stream()
                .collect(Collectors.groupingBy(AgentCapabilityProfileAssignment::getAgentId));
        for (Map.Entry<UUID, List<AgentCapabilityProfileAssignment>> entry : byAgent.entrySet()) {
            List<UUID> profileIds = entry.getValue().stream()
                    .map(AgentCapabilityProfileAssignment::getProfileId)
                    .collect(Collectors.toList());
            agentAllowedCapabilities.put(entry.getKey(), computeAllowedCapabilities(profileIds));
        }
        return byAgent.size();
    }

    public boolean isCapabilityAllowed(UUID agentId, String publicName, String type) {
        Map<String, Set<String>> byType = agentAllowedCapabilities.get(agentId);
        if (byType == null) {
            return false;
        }
        Set<String> allowed = byType.get(type);
        return allowed != null && allowed.contains(publicName);
    }

    public Set<String> getAllowedCapabilities(UUID agentId, String type) {
        Map<String, Set<String>> byType = agentAllowedCapabilities.get(agentId);
        if (byType == null) {
            return Set.of();
        }
        return byType.getOrDefault(type, Set.of());
    }

    public UUID resolveAgentId(String sessionId) {
        return agentRegistryService.getAgentIdForSession(sessionId);
    }

    public boolean hasProfiles(UUID agentId) {
        return agentAllowedCapabilities.containsKey(agentId);
    }

    @Transactional(readOnly = true)
    public void recomputeAgentAccess(UUID agentId) {
        List<AgentCapabilityProfileAssignment> assignments = assignmentRepository.findByAgentId(agentId);

        if (assignments.isEmpty()) {
            agentAllowedCapabilities.remove(agentId);
 log.info("Agent {} removed from capability filter cache (no profiles)", agentId);
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
 log.info("Recomputed access for agent {}: {} tools, {} prompts, {} resources",
                agentId, tools, prompts, resources);
    }

    @Transactional(readOnly = true)
    public void recomputeForProfile(UUID profileId) {
        List<AgentCapabilityProfileAssignment> assignments = assignmentRepository.findByProfileId(profileId);
        for (AgentCapabilityProfileAssignment assignment : assignments) {
            recomputeAgentAccess(assignment.getAgentId());
        }
    }

    public List<AgentCapabilityProfile> getAllProfiles() {
        return profileRepository.findAll();
    }

    private Map<String, Set<String>> computeAllowedCapabilities(List<UUID> profileIds) {
        Set<String> allowedTools = new HashSet<>();
        Set<String> allowedPrompts = new HashSet<>();
        Set<String> allowedResources = new HashSet<>();
        Set<String> allowedSkills = new HashSet<>();

        for (UUID profileId : profileIds) {
            Optional<AgentCapabilityProfile> optProfile = profileRepository.findById(profileId);
            if (optProfile.isEmpty()) continue;

            AgentCapabilityProfile profile = optProfile.get();
            for (AgentCapabilityProfileRule rule : profile.getRules()) {
                String server = rule.getServerConfigName();
                String type = rule.getCapabilityType();
                String mode = rule.getMode();

                List<CapabilityDescriptor> serverCaps = registryService.getCapabilitiesByServer(server);

                List<CapabilityDescriptor> filtered;
                if ("ALL".equals(type)) {
                    filtered = serverCaps;
                } else {
                    CapabilityType capType = CapabilityType.valueOf(type);
                    filtered = serverCaps.stream()
                            .filter(d -> d.getType() == capType)
                            .collect(Collectors.toList());
                }

                Set<String> namedOriginals = parseCapabilityNames(rule.getCapabilityNames());

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

                for (String publicName : resolvedNames) {
                    Optional<CapabilityDescriptor> desc = registryService.lookupByPublicName(publicName);
                    if (desc.isEmpty()) continue;
                    switch (desc.get().getType()) {
                        case TOOL -> allowedTools.add(publicName);
                        case PROMPT -> allowedPrompts.add(publicName);
                        case RESOURCE -> allowedResources.add(publicName);
                        case SKILL -> allowedSkills.add(publicName);
                    }
                }
            }
        }

        Map<String, Set<String>> result = new HashMap<>();
        result.put("TOOL", Collections.unmodifiableSet(allowedTools));
        result.put("PROMPT", Collections.unmodifiableSet(allowedPrompts));
        result.put("RESOURCE", Collections.unmodifiableSet(allowedResources));
        result.put("SKILL", Collections.unmodifiableSet(allowedSkills));
        return result;
    }

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
