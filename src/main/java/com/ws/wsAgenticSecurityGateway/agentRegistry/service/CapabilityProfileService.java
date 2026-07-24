package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfile;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfileAssignment;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfileRule;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileAssignmentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.event.CapabilityProfileChangedEvent;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import org.springframework.context.ApplicationEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CapabilityProfileService {

    private final AgentCapabilityProfileRepository profileRepository;
    private final AgentCapabilityProfileAssignmentRepository assignmentRepository;
    private final AgentCapabilityFilterService filterService;
    private final CapabilityRegistryService registryService;
    private final GatewayAgentRepository agentRepository;
    private final GatewayAuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public CapabilityProfileService(AgentCapabilityProfileRepository profileRepository,
                                     AgentCapabilityProfileAssignmentRepository assignmentRepository,
                                     AgentCapabilityFilterService filterService,
                                     CapabilityRegistryService registryService,
                                     GatewayAgentRepository agentRepository,
                                     GatewayAuditService auditService,
                                     ApplicationEventPublisher eventPublisher) {
        this.profileRepository = profileRepository;
        this.assignmentRepository = assignmentRepository;
        this.filterService = filterService;
        this.registryService = registryService;
        this.agentRepository = agentRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    public List<AgentCapabilityProfile> findAllProfiles() {
        return profileRepository.findAllByWsTenantName(TenantContext.get());
    }

    public List<AgentCapabilityProfile> findTemplates() {
        return profileRepository.findByIsTemplateTrueAndWsTenantName(TenantContext.get());
    }

    public Optional<AgentCapabilityProfile> findProfileById(UUID id) {
        return profileRepository.findById(id)
                .filter(entity -> entity.getWsTenantName() != null
                        && entity.getWsTenantName().equals(TenantContext.get()));
    }

    @Transactional
    public AgentCapabilityProfile createProfile(String name, String description,
                                                 Boolean isTemplate,
                                                 List<Map<String, Object>> rulesData) {
        AgentCapabilityProfile profile = AgentCapabilityProfile.builder()
                .name(name)
                .description(description)
                .isTemplate(isTemplate != null ? isTemplate : false)
                .wsTenantName(TenantContext.get())
                .build();

        if (rulesData != null) {
            for (Map<String, Object> ruleData : rulesData) {
                AgentCapabilityProfileRule rule = parseRule(ruleData, profile);
                profile.getRules().add(rule);
            }
        }

        AgentCapabilityProfile saved = profileRepository.save(profile);
        log.info("Created capability profile: {} (id={})", saved.getName(), saved.getId());
        auditService.auditCapabilityProfileCreated(saved.getName(), saved.getId(),
                description, rulesData != null ? rulesData.size() : 0);
        return saved;
    }

    public boolean existsByName(String name) {
        return profileRepository.findByNameAndWsTenantName(name, TenantContext.get()).isPresent();
    }

    @Transactional
    public AgentCapabilityProfile updateProfile(UUID id, Map<String, Object> request) {
        AgentCapabilityProfile profile = findProfileById(id)
                .orElseThrow(() -> new NoSuchElementException("Profile not found: " + id));

        List<String> changed = new ArrayList<>();
        if (request.containsKey("name") && !Objects.equals(profile.getName(), request.get("name"))) {
            changed.add("name");
            profile.setName((String) request.get("name"));
        } else if (request.containsKey("name")) {
            profile.setName((String) request.get("name"));
        }
        if (request.containsKey("description") && !Objects.equals(profile.getDescription(), request.get("description"))) {
            changed.add("description");
            profile.setDescription((String) request.get("description"));
        } else if (request.containsKey("description")) {
            profile.setDescription((String) request.get("description"));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesData = (List<Map<String, Object>>) request.get("rules");
        if (rulesData != null) {
            changed.add("rules");
            profile.getRules().clear();
            for (Map<String, Object> ruleData : rulesData) {
                AgentCapabilityProfileRule rule = parseRule(ruleData, profile);
                profile.getRules().add(rule);
            }
        }

        AgentCapabilityProfile saved = profileRepository.save(profile);
        filterService.recomputeForProfile(id);

        log.info("Updated capability profile: {} (id={})", saved.getName(), saved.getId());
        auditService.auditCapabilityProfileUpdated(saved.getName(), saved.getId(),
                String.join(", ", changed));

        List<String> affectedAgentNames = assignmentRepository.findByProfileId(id).stream()
                .map(a -> getAgentName(a.getAgentId()))
                .collect(Collectors.toList());
        if (!affectedAgentNames.isEmpty()) {
            eventPublisher.publishEvent(new CapabilityProfileChangedEvent(
                    "PROFILE_UPDATED", saved.getName(), id, affectedAgentNames));
        }
        return saved;
    }

    @Transactional
    public void deleteProfile(UUID id) {
        AgentCapabilityProfile profile = findProfileById(id)
                .orElseThrow(() -> new NoSuchElementException("Profile not found: " + id));

        List<AgentCapabilityProfileAssignment> assignments = assignmentRepository.findByProfileId(id);
        List<UUID> affectedAgentIds = assignments.stream()
                .map(AgentCapabilityProfileAssignment::getAgentId)
                .collect(Collectors.toList());

        profileRepository.deleteById(id);

        for (UUID agentId : affectedAgentIds) {
            filterService.recomputeAgentAccess(agentId);
        }

        log.info("Deleted capability profile: {} (id={})", profile.getName(), id);
        auditService.auditCapabilityProfileDeleted(profile.getName(), id);

        List<String> affectedAgentNames = affectedAgentIds.stream()
                .map(this::getAgentName)
                .collect(Collectors.toList());
        if (!affectedAgentNames.isEmpty()) {
            eventPublisher.publishEvent(new CapabilityProfileChangedEvent(
                    "PROFILE_DELETED", profile.getName(), id, affectedAgentNames));
        }
    }

    @Transactional
    public void assignProfile(UUID profileId, UUID agentId) {
        AgentCapabilityProfile profile = findProfileById(profileId)
                .orElseThrow(() -> new NoSuchElementException("Profile not found: " + profileId));

        if (assignmentRepository.findByAgentIdAndProfileId(agentId, profileId).isPresent()) {
            throw new IllegalStateException("Profile already assigned to this agent");
        }

        AgentCapabilityProfileAssignment assignment = AgentCapabilityProfileAssignment.builder()
                .agentId(agentId)
                .profileId(profileId)
                .build();
        assignmentRepository.save(assignment);
        filterService.recomputeAgentAccess(agentId);

        String agentName = getAgentName(agentId);
        log.info("Assigned profile {} to agent {}", profileId, agentId);
        auditService.auditCapabilityProfileAssigned(profile.getName(), profileId, agentName, agentId);

        eventPublisher.publishEvent(new CapabilityProfileChangedEvent(
                "PROFILE_ASSIGNED", profile.getName(), profileId, List.of(agentName)));
    }

    @Transactional
    public void unassignProfile(UUID profileId, UUID agentId) {
        AgentCapabilityProfileAssignment assignment = assignmentRepository
                .findByAgentIdAndProfileId(agentId, profileId)
                .orElseThrow(() -> new NoSuchElementException("Assignment not found"));

        assignmentRepository.delete(assignment);
        filterService.recomputeAgentAccess(agentId);

        String profileName = profileRepository.findById(profileId)
                .map(AgentCapabilityProfile::getName).orElse("unknown");
        String agentName = getAgentName(agentId);
        log.info("Unassigned profile {} from agent {}", profileId, agentId);
        auditService.auditCapabilityProfileUnassigned(profileName, profileId, agentName, agentId);

        eventPublisher.publishEvent(new CapabilityProfileChangedEvent(
                "PROFILE_UNASSIGNED", profileName, profileId, List.of(agentName)));
    }

    public Map<String, Object> computeProfilePreview(AgentCapabilityProfile profile) {
        Set<String> allowedTools = new HashSet<>();
        Set<String> allowedPrompts = new HashSet<>();
        Set<String> allowedResources = new HashSet<>();

        for (AgentCapabilityProfileRule rule : profile.getRules()) {
            List<CapabilityDescriptor> serverCaps = registryService.getCapabilitiesByServer(
                    rule.getServerConfigName());

            String type = rule.getCapabilityType();
            List<CapabilityDescriptor> filtered;
            if ("ALL".equals(type)) {
                filtered = serverCaps;
            } else {
                CapabilityDescriptor.CapabilityType capType =
                        CapabilityDescriptor.CapabilityType.valueOf(type);
                filtered = serverCaps.stream()
                        .filter(d -> d.getType() == capType)
                        .collect(Collectors.toList());
            }

            Set<String> namedOriginals = parseNames(rule.getCapabilityNames());

            Set<String> resolvedNames;
            switch (rule.getMode()) {
                case "INCLUDE_ALL" -> resolvedNames = filtered.stream()
                        .map(CapabilityDescriptor::getPublicName)
                        .collect(Collectors.toSet());
                case "INCLUDE_ONLY" -> resolvedNames = filtered.stream()
                        .filter(d -> namedOriginals.contains(d.getOriginalName()))
                        .map(CapabilityDescriptor::getPublicName)
                        .collect(Collectors.toSet());
                case "EXCLUDE" -> resolvedNames = filtered.stream()
                        .filter(d -> !namedOriginals.contains(d.getOriginalName()))
                        .map(CapabilityDescriptor::getPublicName)
                        .collect(Collectors.toSet());
                default -> resolvedNames = Set.of();
            }

            for (String name : resolvedNames) {
                registryService.lookupByPublicName(name).ifPresent(desc -> {
                    switch (desc.getType()) {
                        case TOOL -> allowedTools.add(name);
                        case PROMPT -> allowedPrompts.add(name);
                        case RESOURCE -> allowedResources.add(name);
                    }
                });
            }
        }

        Collection<CapabilityDescriptor> allCaps = registryService.getAllCapabilities();
        Map<String, Map<String, List<CapabilityDescriptor>>> byServer = allCaps.stream()
                .collect(Collectors.groupingBy(CapabilityDescriptor::getServerConfigName,
                        Collectors.groupingBy(d -> d.getType().name())));

        Set<String> allAllowed = new HashSet<>();
        allAllowed.addAll(allowedTools);
        allAllowed.addAll(allowedPrompts);
        allAllowed.addAll(allowedResources);

        Map<String, Map<String, Object>> serverBreakdown = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<CapabilityDescriptor>>> entry : byServer.entrySet()) {
            String server = entry.getKey();
            Map<String, List<CapabilityDescriptor>> byType = entry.getValue();
            List<CapabilityDescriptor> sTools = byType.getOrDefault("TOOL", List.of());
            List<CapabilityDescriptor> sPrompts = byType.getOrDefault("PROMPT", List.of());
            List<CapabilityDescriptor> sResources = byType.getOrDefault("RESOURCE", List.of());

            Map<String, Object> bd = new LinkedHashMap<>();
            bd.put("totalTools", sTools.size());
            bd.put("totalPrompts", sPrompts.size());
            bd.put("totalResources", sResources.size());
            bd.put("allowedTools", sTools.stream().filter(d -> allAllowed.contains(d.getPublicName())).count());
            bd.put("allowedPrompts", sPrompts.stream().filter(d -> allAllowed.contains(d.getPublicName())).count());
            bd.put("allowedResources", sResources.stream().filter(d -> allAllowed.contains(d.getPublicName())).count());
            serverBreakdown.put(server, bd);
        }

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("profileName", profile.getName());
        preview.put("serverBreakdown", serverBreakdown);
        preview.put("toolCount", allowedTools.size());
        preview.put("promptCount", allowedPrompts.size());
        preview.put("resourceCount", allowedResources.size());
        return preview;
    }

    public Map<String, Object> computeAgentAccess(UUID agentId) {
        Set<String> allowedTools = filterService.getAllowedCapabilities(agentId, "TOOL");
        Set<String> allowedPrompts = filterService.getAllowedCapabilities(agentId, "PROMPT");
        Set<String> allowedResources = filterService.getAllowedCapabilities(agentId, "RESOURCE");

        List<AgentCapabilityProfileAssignment> assignments = assignmentRepository.findByAgentId(agentId);
        List<Map<String, String>> assignedProfiles = assignments.stream()
                .map(a -> profileRepository.findById(a.getProfileId()).orElse(null))
                .filter(Objects::nonNull)
                .map(p -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("id", p.getId().toString());
                    m.put("name", p.getName());
                    m.put("description", p.getDescription());
                    return m;
                })
                .collect(Collectors.toList());

        Collection<CapabilityDescriptor> allCaps = registryService.getAllCapabilities();
        Map<String, Map<String, List<CapabilityDescriptor>>> byServer = allCaps.stream()
                .collect(Collectors.groupingBy(CapabilityDescriptor::getServerConfigName,
                        Collectors.groupingBy(d -> d.getType().name())));

        Set<String> allAllowed = new HashSet<>();
        allAllowed.addAll(allowedTools);
        allAllowed.addAll(allowedPrompts);
        allAllowed.addAll(allowedResources);

        Map<String, Map<String, Object>> serverBreakdown = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<CapabilityDescriptor>>> entry : byServer.entrySet()) {
            String server = entry.getKey();
            Map<String, List<CapabilityDescriptor>> byType = entry.getValue();
            List<CapabilityDescriptor> sTools = byType.getOrDefault("TOOL", List.of());
            List<CapabilityDescriptor> sPrompts = byType.getOrDefault("PROMPT", List.of());
            List<CapabilityDescriptor> sResources = byType.getOrDefault("RESOURCE", List.of());

            Map<String, Object> bd = new LinkedHashMap<>();
            bd.put("totalTools", sTools.size());
            bd.put("totalPrompts", sPrompts.size());
            bd.put("totalResources", sResources.size());
            bd.put("allowedTools", sTools.stream().filter(d -> allAllowed.contains(d.getPublicName())).count());
            bd.put("allowedPrompts", sPrompts.stream().filter(d -> allAllowed.contains(d.getPublicName())).count());
            bd.put("allowedResources", sResources.stream().filter(d -> allAllowed.contains(d.getPublicName())).count());
            serverBreakdown.put(server, bd);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId.toString());
        result.put("assignedProfiles", assignedProfiles);
        result.put("serverBreakdown", serverBreakdown);
        result.put("summary", Map.of(
                "allowedTools", allowedTools.size(),
                "allowedPrompts", allowedPrompts.size(),
                "allowedResources", allowedResources.size()));
        return result;
    }

    public List<Map<String, Object>> getAvailableServersForBuilder() {
        Collection<CapabilityDescriptor> allCaps = registryService.getAllCapabilities();

        Map<String, Map<String, List<String>>> byServer = new LinkedHashMap<>();
        for (CapabilityDescriptor cap : allCaps) {
            byServer.computeIfAbsent(cap.getServerConfigName(), k -> new HashMap<>())
                    .computeIfAbsent(cap.getType().name(), k -> new ArrayList<>())
                    .add(cap.getOriginalName());
        }

        List<Map<String, Object>> servers = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<String>>> entry : byServer.entrySet()) {
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("serverConfigName", entry.getKey());
            Map<String, List<String>> types = entry.getValue();
            server.put("tools", types.getOrDefault("TOOL", List.of()));
            server.put("prompts", types.getOrDefault("PROMPT", List.of()));
            server.put("resources", types.getOrDefault("RESOURCE", List.of()));
            server.put("toolCount", types.getOrDefault("TOOL", List.of()).size());
            server.put("promptCount", types.getOrDefault("PROMPT", List.of()).size());
            server.put("resourceCount", types.getOrDefault("RESOURCE", List.of()).size());
            servers.add(server);
        }
        return servers;
    }

    public Map<String, Object> toProfileResponse(AgentCapabilityProfile profile) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", profile.getId().toString());
        map.put("name", profile.getName());
        map.put("description", profile.getDescription());
        map.put("isTemplate", profile.getIsTemplate());
        map.put("createdAt", profile.getCreatedAt() != null ? profile.getCreatedAt().toString() : null);
        map.put("updatedAt", profile.getUpdatedAt() != null ? profile.getUpdatedAt().toString() : null);

        List<Map<String, Object>> rules = new ArrayList<>();
        if (profile.getRules() != null) {
            for (AgentCapabilityProfileRule rule : profile.getRules()) {
                Map<String, Object> ruleMap = new LinkedHashMap<>();
                ruleMap.put("id", rule.getId() != null ? rule.getId().toString() : null);
                ruleMap.put("serverConfigName", rule.getServerConfigName());
                ruleMap.put("capabilityType", rule.getCapabilityType());
                ruleMap.put("mode", rule.getMode());
                ruleMap.put("capabilityNames", rule.getCapabilityNames());
                rules.add(ruleMap);
            }
        }
        map.put("rules", rules);

        long assignedCount = assignmentRepository.countByProfileId(profile.getId());
        map.put("assignedAgentCount", assignedCount);
        return map;
    }

    public List<Map<String, String>> getAssignedAgentDetails(UUID profileId) {
        List<AgentCapabilityProfileAssignment> assigns = assignmentRepository.findByProfileId(profileId);
        return assigns.stream()
                .map(a -> agentRepository.findById(a.getAgentId()).orElse(null))
                .filter(Objects::nonNull)
                .map(agent -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("agentId", agent.getId().toString());
                    m.put("agentName", agent.getAgentName());
                    m.put("agentVersion", agent.getAgentVersion());
                    return m;
                })
                .collect(Collectors.toList());
    }

    public String getAgentName(UUID agentId) {
        return agentRepository.findById(agentId)
                .map(GatewayAgentEntity::getAgentName)
                .orElse("unknown");
    }

    AgentCapabilityProfileRule parseRule(Map<String, Object> ruleData,
                                         AgentCapabilityProfile profile) {
        String serverConfigName = (String) ruleData.get("serverConfigName");
        String capabilityType = (String) ruleData.getOrDefault("capabilityType", "ALL");
        String mode = (String) ruleData.get("mode");
        Object capabilityNamesObj = ruleData.get("capabilityNames");

        String capabilityNames = null;
        if (capabilityNamesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> namesList = (List<String>) capabilityNamesObj;
            capabilityNames = String.join(",", namesList);
        } else if (capabilityNamesObj instanceof String) {
            capabilityNames = (String) capabilityNamesObj;
        }

        return AgentCapabilityProfileRule.builder()
                .profile(profile)
                .wsTenantName(profile.getWsTenantName())
                .serverConfigName(serverConfigName)
                .capabilityType(capabilityType)
                .mode(mode)
                .capabilityNames(capabilityNames)
                .build();
    }

    Set<String> parseNames(String names) {
        if (names == null || names.isBlank()) return Set.of();
        return Arrays.stream(names.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
