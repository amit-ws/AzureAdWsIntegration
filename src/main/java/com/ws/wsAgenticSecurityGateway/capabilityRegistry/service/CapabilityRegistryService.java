package com.ws.wsAgenticSecurityGateway.capabilityRegistry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.entity.McpPromptEntity;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.entity.McpResourceEntity;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.entity.McpServerEntity;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.entity.McpToolEntity;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor.CapabilityType;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.repository.McpPromptRepository;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.repository.McpResourceRepository;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.repository.McpServerRepository;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.repository.McpToolRepository;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Core Capability Registry Service for the WS MCP Gateway.
 *
 * <p>Maintains a persistent store (4 PostgreSQL tables) and an in-memory
 * {@link ConcurrentHashMap} for O(1) lock-free lookups on the hot path.
 *
 * <h3>Thread Safety</h3>
 * <ul>
 *   <li><strong>Reads</strong> — lock-free via ConcurrentHashMap</li>
 *   <li><strong>Writes</strong> — serialised via {@code @Transactional} +
 *       caller-level synchronization (McpSessionManager.connect is synchronized)</li>
 * </ul>
 *
 * <h3>Namespacing</h3>
 * Every capability is exposed with a public name of the form
 * {@code <serverConfigName>.<originalName>} to prevent collisions across
 * enterprise MCP servers (e.g. {@code github.create_issue}).
 */
@Service
@Slf4j
public class CapabilityRegistryService {

    // ── Repositories ───────────────────────────────────────────────────
    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final McpResourceRepository resourceRepository;
    private final McpPromptRepository promptRepository;

    // ── Audit ──────────────────────────────────────────────────────────
    private final McpAuditService auditService;

    // ── In-Memory Indexes ──────────────────────────────────────────────
    /**
     * Primary index: publicName → CapabilityDescriptor (O(1) lookup).
     */
    private final ConcurrentHashMap<String, CapabilityDescriptor> primaryIndex =
            new ConcurrentHashMap<>();

    /**
     * Secondary index: serverConfigName → Set<publicName> (server-scoped).
     */
    private final ConcurrentHashMap<String, Set<String>> serverIndex =
            new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public CapabilityRegistryService(McpServerRepository serverRepository,
                                     McpToolRepository toolRepository,
                                     McpResourceRepository resourceRepository,
                                     McpPromptRepository promptRepository,
                                     McpAuditService auditService) {
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.resourceRepository = resourceRepository;
        this.promptRepository = promptRepository;
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
    }

    // ════════════════════════════════════════════════════════════════════
    //  STARTUP — Warm in-memory cache from DB
    // ════════════════════════════════════════════════════════════════════

    @PostConstruct
    public void loadFromDatabase() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("🗂️  CAPABILITY REGISTRY — Loading from database...");
        log.info("═══════════════════════════════════════════════════════════");
        long start = System.currentTimeMillis();

        try {
            List<McpServerEntity> servers = serverRepository.findByStatus("ACTIVE");
            int totalTools = 0, totalResources = 0, totalPrompts = 0;

            for (McpServerEntity server : servers) {
                String configName = server.getServerConfigName();
                Set<String> publicNames = new HashSet<>();

                // Load tools
                List<McpToolEntity> tools = toolRepository.findByServerId(server.getId());
                for (McpToolEntity tool : tools) {
                    CapabilityDescriptor descriptor = toToolDescriptor(tool, configName);
                    primaryIndex.put(descriptor.getPublicName(), descriptor);
                    publicNames.add(descriptor.getPublicName());
                }
                totalTools += tools.size();

                // Load resources
                List<McpResourceEntity> resources = resourceRepository.findByServerId(server.getId());
                for (McpResourceEntity resource : resources) {
                    CapabilityDescriptor descriptor = toResourceDescriptor(resource, configName);
                    primaryIndex.put(descriptor.getPublicName(), descriptor);
                    publicNames.add(descriptor.getPublicName());
                }
                totalResources += resources.size();

                // Load prompts
                List<McpPromptEntity> prompts = promptRepository.findByServerId(server.getId());
                for (McpPromptEntity prompt : prompts) {
                    CapabilityDescriptor descriptor = toPromptDescriptor(prompt, configName);
                    primaryIndex.put(descriptor.getPublicName(), descriptor);
                    publicNames.add(descriptor.getPublicName());
                }
                totalPrompts += prompts.size();

                serverIndex.put(configName, publicNames);
                log.info("   ✅ Server '{}': {} tools, {} resources, {} prompts",
                        configName, tools.size(), resources.size(), prompts.size());
            }

            long duration = System.currentTimeMillis() - start;
            log.info("═══════════════════════════════════════════════════════════");
            log.info("🗂️  CAPABILITY REGISTRY — Loaded {} servers, {} tools, {} resources, {} prompts in {}ms",
                    servers.size(), totalTools, totalResources, totalPrompts, duration);
            log.info("═══════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("❌ Failed to load capability registry from database: {}", e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  WRITE — Register server + all capabilities
    // ════════════════════════════════════════════════════════════════════

    /**
     * Register or update a server and all its discovered capabilities.
     *
     * <p>Called by {@code McpSessionManager} after a successful connection
     * to an enterprise MCP server. Persists to DB and updates in-memory indexes.
     *
     * @param serverConfigName config-level name (e.g. "github")
     * @param displayName      server display name from initialization
     * @param version          server version
     * @param protocolVersion  negotiated protocol version
     * @param capabilities     server capabilities JSON
     * @param tools            discovered tools (may be null)
     * @param resources        discovered resources (may be null)
     * @param prompts          discovered prompts (may be null)
     */
    @Transactional
    public void registerServer(String sessionId,
                               String serverConfigName,
                               String displayName,
                               String version,
                               String protocolVersion,
                               JsonNode capabilities,
                               List<McpSchema.Tool> tools,
                               List<McpSchema.Resource> resources,
                               List<McpSchema.Prompt> prompts) {

        long start = System.currentTimeMillis();
        log.info("🗂️  Registering server '{}' in capability registry...", serverConfigName);

        try {
            // ── 1. Upsert server entity ────────────────────────────
            McpServerEntity serverEntity = serverRepository
                    .findByServerConfigName(serverConfigName)
                    .map(existing -> {
                        existing.setDisplayName(displayName);
                        existing.setVersion(version);
                        existing.setProtocolVersion(protocolVersion);
                        existing.setStatus("ACTIVE");
                        existing.setCapabilities(capabilities);
                        return existing;
                    })
                    .orElse(McpServerEntity.builder()
                            .serverConfigName(serverConfigName)
                            .displayName(displayName)
                            .version(version)
                            .protocolVersion(protocolVersion)
                            .status("ACTIVE")
                            .capabilities(capabilities)
                            .build());

            serverEntity = serverRepository.saveAndFlush(serverEntity);
            UUID serverId = serverEntity.getId();

            // ── 2. Remove existing capabilities (clean slate) ──────
            evictServerFromIndexes(serverConfigName);
            toolRepository.deleteByServerId(serverId);
            toolRepository.flush();
            resourceRepository.deleteByServerId(serverId);
            resourceRepository.flush();
            promptRepository.deleteByServerId(serverId);
            promptRepository.flush();

            Set<String> publicNames = new HashSet<>();

            // ── 3. Persist tools ───────────────────────────────────
            int toolCount = 0;
            if (tools != null) {
                for (McpSchema.Tool tool : tools) {
                    String publicName = buildPublicName(serverConfigName, tool.name());

                    // Serialize JsonSchema → String for DB persistence
                    String inputSchemaStr = null;
                    if (tool.inputSchema() != null) {
                        try {
                            inputSchemaStr = objectMapper.writeValueAsString(tool.inputSchema());
                        } catch (Exception e) {
                            log.warn("Failed to serialize inputSchema for tool '{}': {}",
                                    tool.name(), e.getMessage());
                        }
                    }

                    McpToolEntity entity = McpToolEntity.builder()
                            .server(serverEntity)
                            .toolName(tool.name())
                            .publicName(publicName)
                            .description(tool.description())
                            .inputSchema(inputSchemaStr)
                            .build();
                    toolRepository.save(entity);

                    CapabilityDescriptor descriptor = CapabilityDescriptor.builder()
                            .publicName(publicName)
                            .originalName(tool.name())
                            .serverConfigName(serverConfigName)
                            .type(CapabilityType.TOOL)
                            .description(tool.description())
                            .inputSchema(inputSchemaStr)
                            .serverId(serverId)
                            .build();
                    primaryIndex.put(publicName, descriptor);
                    publicNames.add(publicName);
                    toolCount++;

                    auditService.auditRegistryCapabilityRegistered(
                            sessionId, serverConfigName, publicName, "TOOL");
                }
            }

            // ── 4. Persist resources ───────────────────────────────
            int resourceCount = 0;
            if (resources != null) {
                for (McpSchema.Resource resource : resources) {
                    String publicName = buildPublicName(serverConfigName, resource.name());
                    McpResourceEntity entity = McpResourceEntity.builder()
                            .server(serverEntity)
                            .resourceUri(resource.uri())
                            .publicName(publicName)
                            .name(resource.name())
                            .description(resource.description())
                            .mimeType(resource.mimeType())
                            .build();
                    resourceRepository.save(entity);

                    CapabilityDescriptor descriptor = CapabilityDescriptor.builder()
                            .publicName(publicName)
                            .originalName(resource.name())
                            .serverConfigName(serverConfigName)
                            .type(CapabilityType.RESOURCE)
                            .description(resource.description())
                            .resourceUri(resource.uri())
                            .mimeType(resource.mimeType())
                            .serverId(serverId)
                            .build();
                    primaryIndex.put(publicName, descriptor);
                    publicNames.add(publicName);
                    resourceCount++;

                    auditService.auditRegistryCapabilityRegistered(
                            sessionId, serverConfigName, publicName, "RESOURCE");
                }
            }

            // ── 5. Persist prompts ─────────────────────────────────
            int promptCount = 0;
            if (prompts != null) {
                for (McpSchema.Prompt prompt : prompts) {
                    String publicName = buildPublicName(serverConfigName, prompt.name());

                    JsonNode argsJson = null;
                    if (prompt.arguments() != null) {
                        argsJson = objectMapper.valueToTree(prompt.arguments());
                    }

                    McpPromptEntity entity = McpPromptEntity.builder()
                            .server(serverEntity)
                            .promptName(prompt.name())
                            .publicName(publicName)
                            .description(prompt.description())
                            .arguments(argsJson)
                            .build();
                    promptRepository.save(entity);

                    CapabilityDescriptor descriptor = CapabilityDescriptor.builder()
                            .publicName(publicName)
                            .originalName(prompt.name())
                            .serverConfigName(serverConfigName)
                            .type(CapabilityType.PROMPT)
                            .description(prompt.description())
                            .arguments(argsJson != null ? argsJson.toString() : null)
                            .serverId(serverId)
                            .build();
                    primaryIndex.put(publicName, descriptor);
                    publicNames.add(publicName);
                    promptCount++;

                    auditService.auditRegistryCapabilityRegistered(
                            sessionId, serverConfigName, publicName, "PROMPT");
                }
            }

            // ── 6. Update secondary index ──────────────────────────
            serverIndex.put(serverConfigName, publicNames);

            long duration = System.currentTimeMillis() - start;
            log.info("✅ Server '{}' registered: {} tools, {} resources, {} prompts ({}ms)",
                    serverConfigName, toolCount, resourceCount, promptCount, duration);

            // Audit — bulk load
            auditService.auditRegistryBulkLoad(
                    sessionId, serverConfigName, toolCount, resourceCount, promptCount, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("❌ Failed to register server '{}': {}", serverConfigName, e.getMessage(), e);
            auditService.auditRegistryServerRefresh(
                    sessionId, serverConfigName, AuditStatus.ERROR, e.getMessage(), duration);
            throw e;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  WRITE — Remove server + all capabilities
    // ════════════════════════════════════════════════════════════════════

    /**
     * Remove a server and all its capabilities from DB and in-memory indexes.
     *
     * <p>Called by {@code McpSessionManager.disconnect()}.
     */
    @Transactional
    public void removeServer(String sessionId, String serverConfigName) {
        long start = System.currentTimeMillis();
        log.info("🗑️  Removing server '{}' from capability registry...", serverConfigName);

        try {
            Optional<McpServerEntity> optServer =
                    serverRepository.findByServerConfigName(serverConfigName);

            if (optServer.isPresent()) {
                McpServerEntity serverEntity = optServer.get();
                UUID serverId = serverEntity.getId();

                // Audit each removed capability
                Set<String> publicNames = serverIndex.getOrDefault(serverConfigName, Set.of());
                for (String publicName : publicNames) {
                    CapabilityDescriptor desc = primaryIndex.get(publicName);
                    if (desc != null) {
                        auditService.auditRegistryCapabilityRemoved(
                                sessionId, serverConfigName, publicName, desc.getType().name());
                    }
                }

                // Remove from DB
                toolRepository.deleteByServerId(serverId);
                resourceRepository.deleteByServerId(serverId);
                promptRepository.deleteByServerId(serverId);

                // Mark server inactive (soft delete — keep the record)
                serverEntity.setStatus("INACTIVE");
                serverRepository.save(serverEntity);

                // Evict from in-memory indexes
                evictServerFromIndexes(serverConfigName);

                long duration = System.currentTimeMillis() - start;
                log.info("✅ Server '{}' removed from registry ({}ms)", serverConfigName, duration);

                auditService.auditRegistryServerRefresh(
                        sessionId, serverConfigName, AuditStatus.SUCCESS, null, duration);
            } else {
                log.warn("⚠️  Server '{}' not found in registry — nothing to remove", serverConfigName);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("❌ Failed to remove server '{}': {}", serverConfigName, e.getMessage(), e);
            auditService.auditRegistryServerRefresh(
                    sessionId, serverConfigName, AuditStatus.ERROR, e.getMessage(), duration);
        }
    }

    /**
     * Refresh a server's capabilities — remove existing and re-register.
     *
     * <p>Equivalent to {@code removeServer + registerServer} in a single atomic block.
     */
    @Transactional
    public void refreshServer(String sessionId,
                              String serverConfigName,
                              String displayName,
                              String version,
                              String protocolVersion,
                              JsonNode capabilities,
                              List<McpSchema.Tool> tools,
                              List<McpSchema.Resource> resources,
                              List<McpSchema.Prompt> prompts) {

        // registerServer already handles the "upsert + clean slate" pattern
        registerServer(sessionId, serverConfigName, displayName, version,
                protocolVersion, capabilities, tools, resources, prompts);
    }

    // ════════════════════════════════════════════════════════════════════
    //  READ — Lock-free O(1) lookups
    // ════════════════════════════════════════════════════════════════════

    /**
     * Look up a capability by its namespaced public name.
     * This is the primary hot-path method — O(1), lock-free.
     */
    public Optional<CapabilityDescriptor> lookupByPublicName(String publicName) {
        return Optional.ofNullable(primaryIndex.get(publicName));
    }

    /**
     * Get all registered capabilities (all types).
     */
    public Collection<CapabilityDescriptor> getAllCapabilities() {
        return Collections.unmodifiableCollection(primaryIndex.values());
    }

    /**
     * Get all capabilities belonging to a specific server.
     */
    public List<CapabilityDescriptor> getCapabilitiesByServer(String serverConfigName) {
        Set<String> publicNames = serverIndex.getOrDefault(serverConfigName, Set.of());
        return publicNames.stream()
                .map(primaryIndex::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get all TOOL descriptors across all servers.
     */
    public List<CapabilityDescriptor> getToolDescriptors() {
        return primaryIndex.values().stream()
                .filter(d -> d.getType() == CapabilityType.TOOL)
                .collect(Collectors.toList());
    }

    /**
     * Get all RESOURCE descriptors across all servers.
     */
    public List<CapabilityDescriptor> getResourceDescriptors() {
        return primaryIndex.values().stream()
                .filter(d -> d.getType() == CapabilityType.RESOURCE)
                .collect(Collectors.toList());
    }

    /**
     * Get all PROMPT descriptors across all servers.
     */
    public List<CapabilityDescriptor> getPromptDescriptors() {
        return primaryIndex.values().stream()
                .filter(d -> d.getType() == CapabilityType.PROMPT)
                .collect(Collectors.toList());
    }

    /**
     * Get names of all registered servers.
     */
    public Set<String> getRegisteredServerNames() {
        return Collections.unmodifiableSet(serverIndex.keySet());
    }

    /**
     * Total number of registered capabilities across all servers.
     */
    public int getTotalCapabilityCount() {
        return primaryIndex.size();
    }

    /**
     * Check if a capability exists.
     */
    public boolean exists(String publicName) {
        return primaryIndex.containsKey(publicName);
    }

    // ════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Build a namespaced public name: {@code serverConfigName.originalName}.
     */
    private String buildPublicName(String serverConfigName, String originalName) {
//        return serverConfigName + "." + originalName;
        return serverConfigName + "_" + originalName;
    }

    /**
     * Evict all capabilities of a server from in-memory indexes.
     */
    private void evictServerFromIndexes(String serverConfigName) {
        Set<String> publicNames = serverIndex.remove(serverConfigName);
        if (publicNames != null) {
            for (String publicName : publicNames) {
                primaryIndex.remove(publicName);
            }
        }
    }

    /**
     * Convert a tool entity to an in-memory descriptor.
     */
    private CapabilityDescriptor toToolDescriptor(McpToolEntity entity, String serverConfigName) {
        return CapabilityDescriptor.builder()
                .publicName(entity.getPublicName())
                .originalName(entity.getToolName())
                .serverConfigName(serverConfigName)
                .type(CapabilityType.TOOL)
                .description(entity.getDescription())
                .inputSchema(entity.getInputSchema())
                .serverId(entity.getServer().getId())
                .build();
    }

    /**
     * Convert a resource entity to an in-memory descriptor.
     */
    private CapabilityDescriptor toResourceDescriptor(McpResourceEntity entity, String serverConfigName) {
        return CapabilityDescriptor.builder()
                .publicName(entity.getPublicName())
                .originalName(entity.getName())
                .serverConfigName(serverConfigName)
                .type(CapabilityType.RESOURCE)
                .description(entity.getDescription())
                .resourceUri(entity.getResourceUri())
                .mimeType(entity.getMimeType())
                .serverId(entity.getServer().getId())
                .build();
    }

    /**
     * Convert a prompt entity to an in-memory descriptor.
     */
    private CapabilityDescriptor toPromptDescriptor(McpPromptEntity entity, String serverConfigName) {
        return CapabilityDescriptor.builder()
                .publicName(entity.getPublicName())
                .originalName(entity.getPromptName())
                .serverConfigName(serverConfigName)
                .type(CapabilityType.PROMPT)
                .description(entity.getDescription())
                .arguments(entity.getArguments() != null ? entity.getArguments().toString() : null)
                .serverId(entity.getServer().getId())
                .build();
    }
}
