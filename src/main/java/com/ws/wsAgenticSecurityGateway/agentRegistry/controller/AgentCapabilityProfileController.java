package com.ws.wsAgenticSecurityGateway.agentRegistry.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfile;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfileAssignment;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfileRule;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileAssignmentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileRuleRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentCapabilityFilterService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.CapabilityProfileChatService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for Capability Access Profile management.
 * CRUD operations for profiles, rules, and agent assignments.
 */
@RestController
@RequestMapping("/api/admin/capability-profiles")
@Slf4j
public class AgentCapabilityProfileController {

    private final AgentCapabilityProfileRepository profileRepository;
    private final AgentCapabilityProfileRuleRepository ruleRepository;
    private final AgentCapabilityProfileAssignmentRepository assignmentRepository;
    private final AgentCapabilityFilterService filterService;
    private final CapabilityRegistryService registryService;
    private final CapabilityProfileChatService chatService;
    private final GatewayAgentRepository agentRepository;
    private final McpAuditService auditService;

    public AgentCapabilityProfileController(AgentCapabilityProfileRepository profileRepository,
                                              AgentCapabilityProfileRuleRepository ruleRepository,
                                              AgentCapabilityProfileAssignmentRepository assignmentRepository,
                                              AgentCapabilityFilterService filterService,
                                              CapabilityRegistryService registryService,
                                              CapabilityProfileChatService chatService,
                                              GatewayAgentRepository agentRepository,
                                              McpAuditService auditService) {
        this.profileRepository = profileRepository;
        this.ruleRepository = ruleRepository;
        this.assignmentRepository = assignmentRepository;
        this.filterService = filterService;
        this.registryService = registryService;
        this.chatService = chatService;
        this.agentRepository = agentRepository;
        this.auditService = auditService;
    }

    // ════════════════════════════════════════════════════════════════════
    // PROFILE CRUD
    // ════════════════════════════════════════════════════════════════════

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listProfiles() {
        List<AgentCapabilityProfile> profiles = profileRepository.findAll();
        List<Map<String, Object>> result = profiles.stream()
                .map(this::toProfileResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/templates")
    public ResponseEntity<List<Map<String, Object>>> listTemplates() {
        List<AgentCapabilityProfile> templates = profileRepository.findByIsTemplateTrue();
        List<Map<String, Object>> result = templates.stream()
                .map(this::toProfileResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getProfile(@PathVariable UUID id) {
        return profileRepository.findById(id)
                .map(p -> {
                    Map<String, Object> resp = toProfileResponse(p);
                    // Include assigned agents detail
                    List<AgentCapabilityProfileAssignment> assigns = assignmentRepository.findByProfileId(id);
                    List<Map<String, String>> assignedAgents = assigns.stream()
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
                    resp.put("assignedAgents", assignedAgents);
                    return ResponseEntity.ok(resp);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> createProfile(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String description = (String) request.get("description");
        Boolean isTemplate = (Boolean) request.getOrDefault("isTemplate", false);

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Profile name is required"));
        }

        if (profileRepository.findByName(name).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Profile '" + name + "' already exists"));
        }

        AgentCapabilityProfile profile = AgentCapabilityProfile.builder()
                .name(name)
                .description(description)
                .isTemplate(isTemplate)
                .build();

        // Parse and add rules
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesData = (List<Map<String, Object>>) request.get("rules");
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

        return ResponseEntity.ok(toProfileResponse(saved));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateProfile(@PathVariable UUID id,
                                                               @RequestBody Map<String, Object> request) {
        Optional<AgentCapabilityProfile> opt = profileRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        AgentCapabilityProfile profile = opt.get();

        // Track changed fields for audit
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

        // Replace rules if provided
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

        // Recompute cache for all agents using this profile
        filterService.recomputeForProfile(id);

        log.info("Updated capability profile: {} (id={})", saved.getName(), saved.getId());
        auditService.auditCapabilityProfileUpdated(saved.getName(), saved.getId(),
                String.join(", ", changed));
        return ResponseEntity.ok(toProfileResponse(saved));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteProfile(@PathVariable UUID id) {
        Optional<AgentCapabilityProfile> opt = profileRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Get affected agents before deletion
        List<AgentCapabilityProfileAssignment> assignments = assignmentRepository.findByProfileId(id);
        List<UUID> affectedAgentIds = assignments.stream()
                .map(AgentCapabilityProfileAssignment::getAgentId)
                .collect(Collectors.toList());

        profileRepository.deleteById(id);

        // Recompute cache for affected agents
        for (UUID agentId : affectedAgentIds) {
            filterService.recomputeAgentAccess(agentId);
        }

        log.info("Deleted capability profile: {} (id={})", opt.get().getName(), id);
        auditService.auditCapabilityProfileDeleted(opt.get().getName(), id);
        return ResponseEntity.ok(Map.of("deleted", true, "profileId", id.toString()));
    }

    // ════════════════════════════════════════════════════════════════════
    // PROFILE ASSIGNMENT
    // ════════════════════════════════════════════════════════════════════

    @PostMapping("/{profileId}/assign/{agentId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> assignProfile(@PathVariable UUID profileId,
                                                               @PathVariable UUID agentId) {
        Optional<AgentCapabilityProfile> profileOpt = profileRepository.findById(profileId);
        if (profileOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Profile not found"));
        }
        if (assignmentRepository.findByAgentIdAndProfileId(agentId, profileId).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Profile already assigned to this agent"));
        }

        AgentCapabilityProfileAssignment assignment = AgentCapabilityProfileAssignment.builder()
                .agentId(agentId)
                .profileId(profileId)
                .build();
        assignmentRepository.save(assignment);

        // Recompute agent's allowed capabilities
        filterService.recomputeAgentAccess(agentId);

        String profileName = profileOpt.get().getName();
        String agentName = agentRepository.findById(agentId).map(GatewayAgentEntity::getAgentName).orElse("unknown");
        log.info("Assigned profile {} to agent {}", profileId, agentId);
        auditService.auditCapabilityProfileAssigned(profileName, profileId, agentName, agentId);
        return ResponseEntity.ok(Map.of("assigned", true, "profileId", profileId.toString(),
                "agentId", agentId.toString()));
    }

    @DeleteMapping("/{profileId}/assign/{agentId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> unassignProfile(@PathVariable UUID profileId,
                                                                 @PathVariable UUID agentId) {
        Optional<AgentCapabilityProfileAssignment> opt =
                assignmentRepository.findByAgentIdAndProfileId(agentId, profileId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        assignmentRepository.delete(opt.get());

        // Recompute agent's allowed capabilities
        filterService.recomputeAgentAccess(agentId);

        String profileName = profileRepository.findById(profileId).map(AgentCapabilityProfile::getName).orElse("unknown");
        String agentName = agentRepository.findById(agentId).map(GatewayAgentEntity::getAgentName).orElse("unknown");
        log.info("Unassigned profile {} from agent {}", profileId, agentId);
        auditService.auditCapabilityProfileUnassigned(profileName, profileId, agentName, agentId);
        return ResponseEntity.ok(Map.of("unassigned", true, "profileId", profileId.toString(),
                "agentId", agentId.toString()));
    }

    // ════════════════════════════════════════════════════════════════════
    // PREVIEW / QUERY
    // ════════════════════════════════════════════════════════════════════

    /**
     * Preview what capabilities a profile grants — resolves rules against current registry.
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> previewProfile(@PathVariable UUID id) {
        Optional<AgentCapabilityProfile> opt = profileRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Build a temporary computation using the profile's rules
        Map<String, Object> preview = computeProfilePreview(opt.get());
        return ResponseEntity.ok(preview);
    }

    /**
     * Preview effective access for a specific agent — union of all assigned profiles.
     */
    @GetMapping("/agent-access/{agentId}")
    public ResponseEntity<Map<String, Object>> previewAgentAccess(@PathVariable UUID agentId) {
        Set<String> allowedTools = filterService.getAllowedCapabilities(agentId, "TOOL");
        Set<String> allowedPrompts = filterService.getAllowedCapabilities(agentId, "PROMPT");
        Set<String> allowedResources = filterService.getAllowedCapabilities(agentId, "RESOURCE");

        // Assigned profiles as full objects (id, name, description)
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

        // Server-level breakdown: per server, how many allowed vs total
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

            long aTools = sTools.stream().filter(d -> allAllowed.contains(d.getPublicName())).count();
            long aPrompts = sPrompts.stream().filter(d -> allAllowed.contains(d.getPublicName())).count();
            long aResources = sResources.stream().filter(d -> allAllowed.contains(d.getPublicName())).count();

            Map<String, Object> bd = new LinkedHashMap<>();
            bd.put("totalTools", sTools.size());
            bd.put("totalPrompts", sPrompts.size());
            bd.put("totalResources", sResources.size());
            bd.put("allowedTools", aTools);
            bd.put("allowedPrompts", aPrompts);
            bd.put("allowedResources", aResources);
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

        return ResponseEntity.ok(result);
    }

    /**
     * Get available servers and their capability counts — for the profile builder UI.
     */
    @GetMapping("/available-servers")
    public ResponseEntity<List<Map<String, Object>>> getAvailableServers() {
        Collection<CapabilityDescriptor> allCaps = registryService.getAllCapabilities();

        // Group by server
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

        return ResponseEntity.ok(servers);
    }

    // ════════════════════════════════════════════════════════════════════
    // CHAT ASSISTANT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Chat endpoint for LLM-powered profile generation.
     * Accepts a prompt and optional conversation history.
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chatGenerateProfile(@RequestBody Map<String, Object> request) {
        String prompt = (String) request.get("prompt");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = (List<Map<String, String>>) request.get("conversationHistory");

        Map<String, Object> result = chatService.generateProfile(prompt, history);
        return ResponseEntity.ok(result);
    }

    /**
     * Check if the chat assistant is available (API key configured).
     */
    @GetMapping("/chat/status")
    public ResponseEntity<Map<String, Object>> chatStatus() {
        return ResponseEntity.ok(Map.of("available", chatService.isLlmAvailable()));
    }

    // ════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ════════════════════════════════════════════════════════════════════

    private Map<String, Object> toProfileResponse(AgentCapabilityProfile profile) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", profile.getId().toString());
        map.put("name", profile.getName());
        map.put("description", profile.getDescription());
        map.put("isTemplate", profile.getIsTemplate());
        map.put("createdAt", profile.getCreatedAt() != null ? profile.getCreatedAt().toString() : null);
        map.put("updatedAt", profile.getUpdatedAt() != null ? profile.getUpdatedAt().toString() : null);

        // Rules
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

        // Assignment count
        long assignedCount = assignmentRepository.countByProfileId(profile.getId());
        map.put("assignedAgentCount", assignedCount);

        return map;
    }

    private AgentCapabilityProfileRule parseRule(Map<String, Object> ruleData,
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
                .serverConfigName(serverConfigName)
                .capabilityType(capabilityType)
                .mode(mode)
                .capabilityNames(capabilityNames)
                .build();
    }

    private Map<String, Object> computeProfilePreview(AgentCapabilityProfile profile) {
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

        // Per-server breakdown
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

    private Set<String> parseNames(String names) {
        if (names == null || names.isBlank()) return Set.of();
        return Arrays.stream(names.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
