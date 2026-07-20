package com.ws.wsAgenticSecurityGateway.agentRegistry.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfile;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.CapabilityProfileChatService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.CapabilityProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/capability-profiles")
@Slf4j
public class AgentCapabilityProfileController {

    private final CapabilityProfileService profileService;
    private final CapabilityProfileChatService chatService;

    public AgentCapabilityProfileController(CapabilityProfileService profileService,
                                              CapabilityProfileChatService chatService) {
        this.profileService = profileService;
        this.chatService = chatService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listProfiles() {
        List<AgentCapabilityProfile> profiles = profileService.findAllProfiles();
        List<Map<String, Object>> result = profiles.stream()
                .map(profileService::toProfileResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/templates")
    public ResponseEntity<List<Map<String, Object>>> listTemplates() {
        List<AgentCapabilityProfile> templates = profileService.findTemplates();
        List<Map<String, Object>> result = templates.stream()
                .map(profileService::toProfileResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getProfile(@PathVariable UUID id) {
        return profileService.findProfileById(id)
                .map(p -> {
                    Map<String, Object> resp = profileService.toProfileResponse(p);
                    resp.put("assignedAgents", profileService.getAssignedAgentDetails(id));
                    return ResponseEntity.ok(resp);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createProfile(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String description = (String) request.get("description");
        Boolean isTemplate = (Boolean) request.getOrDefault("isTemplate", false);

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Profile name is required"));
        }

        if (profileService.existsByName(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Profile '" + name + "' already exists"));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesData = (List<Map<String, Object>>) request.get("rules");

        AgentCapabilityProfile saved = profileService.createProfile(name, description, isTemplate, rulesData);
        return ResponseEntity.ok(profileService.toProfileResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateProfile(@PathVariable UUID id,
                                                               @RequestBody Map<String, Object> request) {
        try {
            AgentCapabilityProfile saved = profileService.updateProfile(id, request);
            return ResponseEntity.ok(profileService.toProfileResponse(saved));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteProfile(@PathVariable UUID id) {
        try {
            profileService.deleteProfile(id);
            return ResponseEntity.ok(Map.of("deleted", true, "profileId", id.toString()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{profileId}/assign/{agentId}")
    public ResponseEntity<Map<String, Object>> assignProfile(@PathVariable UUID profileId,
                                                               @PathVariable UUID agentId) {
        try {
            profileService.assignProfile(profileId, agentId);
            return ResponseEntity.ok(Map.of("assigned", true, "profileId", profileId.toString(),
                    "agentId", agentId.toString()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Profile not found"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{profileId}/assign/{agentId}")
    public ResponseEntity<Map<String, Object>> unassignProfile(@PathVariable UUID profileId,
                                                                 @PathVariable UUID agentId) {
        try {
            profileService.unassignProfile(profileId, agentId);
            return ResponseEntity.ok(Map.of("unassigned", true, "profileId", profileId.toString(),
                    "agentId", agentId.toString()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> previewProfile(@PathVariable UUID id) {
        return profileService.findProfileById(id)
                .map(profile -> ResponseEntity.ok(profileService.computeProfilePreview(profile)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/agent-access/{agentId}")
    public ResponseEntity<Map<String, Object>> previewAgentAccess(@PathVariable UUID agentId) {
        return ResponseEntity.ok(profileService.computeAgentAccess(agentId));
    }

    @GetMapping("/available-servers")
    public ResponseEntity<List<Map<String, Object>>> getAvailableServers() {
        return ResponseEntity.ok(profileService.getAvailableServersForBuilder());
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chatGenerateProfile(@RequestBody Map<String, Object> request) {
        String prompt = (String) request.get("prompt");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = (List<Map<String, String>>) request.get("conversationHistory");

        Map<String, Object> result = chatService.generateProfile(prompt, history);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/chat/status")
    public ResponseEntity<Map<String, Object>> chatStatus() {
        return ResponseEntity.ok(Map.of("available", chatService.isLlmAvailable()));
    }
}
