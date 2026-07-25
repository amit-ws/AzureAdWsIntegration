package com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor.CapabilityType;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.entity.McpPromptEntity;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.entity.McpResourceEntity;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.entity.McpServerEntity;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.entity.McpToolEntity;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.repository.McpPromptRepository;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.repository.McpResourceRepository;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.repository.McpServerRepository;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.repository.McpToolRepository;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The MCP capability boundary: owns the MCP capability tables ({@code mcp_server/tool/resource/prompt}) and
 * the MCP-typed ingest, and feeds the protocol-neutral {@link CapabilityRegistryService} index the spine
 * queries. This is the one place the MCP SDK's {@code McpSchema.Tool/Resource/Prompt} are read and mapped to
 * neutral {@link CapabilityDescriptor}s — an A2A boundary would have its own registrar over its own tables,
 * feeding the same neutral index.
 *
 * <p>The persistence + descriptor-building logic here was extracted verbatim from the former
 * {@code CapabilityRegistryService.registerServer/removeServer/loadFromDatabase}, so behavior is unchanged;
 * only the in-memory index update now goes through the neutral registry's
 * {@link CapabilityRegistryService#registerServerCapabilities} / {@link CapabilityRegistryService#evictServer}.
 */
@Service
@Slf4j
public class McpCapabilityRegistrar {

    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final McpResourceRepository resourceRepository;
    private final McpPromptRepository promptRepository;
    private final GatewayAuditService auditService;
    private final CapabilityRegistryService registry;
    private final ObjectMapper objectMapper;

    public McpCapabilityRegistrar(McpServerRepository serverRepository,
                                  McpToolRepository toolRepository,
                                  McpResourceRepository resourceRepository,
                                  McpPromptRepository promptRepository,
                                  GatewayAuditService auditService,
                                  CapabilityRegistryService registry) {
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.resourceRepository = resourceRepository;
        this.promptRepository = promptRepository;
        this.auditService = auditService;
        this.registry = registry;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void loadFromDatabase() {
        log.info("MCP CAPABILITY REGISTRAR — Loading from database...");
        long start = System.currentTimeMillis();

        try {
            List<McpServerEntity> servers = serverRepository.findByStatus("ACTIVE");
            int totalTools = 0, totalResources = 0, totalPrompts = 0;

            for (McpServerEntity server : servers) {
                String configName = server.getServerConfigName();
                List<CapabilityDescriptor> descriptors = new ArrayList<>();

                List<McpToolEntity> tools = toolRepository.findByServerId(server.getId());
                for (McpToolEntity tool : tools) {
                    descriptors.add(toToolDescriptor(tool, configName));
                }
                totalTools += tools.size();

                List<McpResourceEntity> resources = resourceRepository.findByServerId(server.getId());
                for (McpResourceEntity resource : resources) {
                    descriptors.add(toResourceDescriptor(resource, configName));
                }
                totalResources += resources.size();

                List<McpPromptEntity> prompts = promptRepository.findByServerId(server.getId());
                for (McpPromptEntity prompt : prompts) {
                    descriptors.add(toPromptDescriptor(prompt, configName));
                }
                totalPrompts += prompts.size();

                registry.registerServerCapabilities(configName, descriptors);
                log.info("Server '{}': {} tools, {} resources, {} prompts",
                        configName, tools.size(), resources.size(), prompts.size());
            }

            long duration = System.currentTimeMillis() - start;
            log.info("MCP CAPABILITY REGISTRAR — Loaded {} servers, {} tools, {} resources, {} prompts in {}ms",
                    servers.size(), totalTools, totalResources, totalPrompts, duration);

        } catch (Exception e) {
            log.error("Failed to load capability registry from database: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void registerServer(String sessionId,
                               String serverConfigName,
                               String displayName,
                               String version,
                               String protocolVersion,
                               JsonNode capabilities,
                               List<McpSchema.Tool> tools,
                               List<McpSchema.Resource> resources,
                               List<McpSchema.Prompt> prompts,
                               String wsTenantName) {

        long start = System.currentTimeMillis();
        String tenant = wsTenantName != null ? wsTenantName : TenantContext.get();
        log.info("Registering server '{}' in capability registry...", serverConfigName);

        try {
            McpServerEntity serverEntity = serverRepository
                    .findByServerConfigName(serverConfigName)
                    .map(existing -> {
                        existing.setDisplayName(displayName);
                        existing.setVersion(version);
                        existing.setProtocolVersion(protocolVersion);
                        existing.setStatus("ACTIVE");
                        existing.setCapabilities(capabilities);
                        if (tenant != null && existing.getWsTenantName() == null) {
                            existing.setWsTenantName(tenant);
                        }
                        return existing;
                    })
                    .orElse(McpServerEntity.builder()
                            .serverConfigName(serverConfigName)
                            .displayName(displayName)
                            .version(version)
                            .protocolVersion(protocolVersion)
                            .status("ACTIVE")
                            .capabilities(capabilities)
                            .wsTenantName(tenant)
                            .build());

            serverEntity = serverRepository.saveAndFlush(serverEntity);
            UUID serverId = serverEntity.getId();

            toolRepository.deleteByServerId(serverId);
            toolRepository.flush();
            resourceRepository.deleteByServerId(serverId);
            resourceRepository.flush();
            promptRepository.deleteByServerId(serverId);
            promptRepository.flush();

            List<CapabilityDescriptor> descriptors = new ArrayList<>();

            int toolCount = 0;
            if (tools != null) {
                for (McpSchema.Tool tool : tools) {
                    String publicName = buildPublicName(serverConfigName, tool.name());

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
                            .wsTenantName(tenant)
                            .build();
                    toolRepository.save(entity);

                    descriptors.add(CapabilityDescriptor.builder()
                            .publicName(publicName)
                            .originalName(tool.name())
                            .serverConfigName(serverConfigName)
                            .type(CapabilityType.TOOL)
                            .description(tool.description())
                            .inputSchema(inputSchemaStr)
                            .serverId(serverId)
                            .build());
                    toolCount++;

                    auditService.auditRegistryCapabilityRegistered(
                            sessionId, serverConfigName, publicName, "TOOL");
                }
            }

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
                            .wsTenantName(tenant)
                            .build();
                    resourceRepository.save(entity);

                    descriptors.add(CapabilityDescriptor.builder()
                            .publicName(publicName)
                            .originalName(resource.name())
                            .serverConfigName(serverConfigName)
                            .type(CapabilityType.RESOURCE)
                            .description(resource.description())
                            .resourceUri(resource.uri())
                            .mimeType(resource.mimeType())
                            .serverId(serverId)
                            .build());
                    resourceCount++;

                    auditService.auditRegistryCapabilityRegistered(
                            sessionId, serverConfigName, publicName, "RESOURCE");
                }
            }

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
                            .wsTenantName(tenant)
                            .build();
                    promptRepository.save(entity);

                    descriptors.add(CapabilityDescriptor.builder()
                            .publicName(publicName)
                            .originalName(prompt.name())
                            .serverConfigName(serverConfigName)
                            .type(CapabilityType.PROMPT)
                            .description(prompt.description())
                            .arguments(argsJson != null ? argsJson.toString() : null)
                            .serverId(serverId)
                            .build());
                    promptCount++;

                    auditService.auditRegistryCapabilityRegistered(
                            sessionId, serverConfigName, publicName, "PROMPT");
                }
            }

            registry.registerServerCapabilities(serverConfigName, descriptors);

            long duration = System.currentTimeMillis() - start;
            log.info("Server '{}' registered: {} tools, {} resources, {} prompts ({}ms)",
                    serverConfigName, toolCount, resourceCount, promptCount, duration);

            auditService.auditRegistryBulkLoad(
                    sessionId, serverConfigName, toolCount, resourceCount, promptCount, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Failed to register server '{}': {}", serverConfigName, e.getMessage(), e);
            auditService.auditRegistryServerRefresh(
                    sessionId, serverConfigName, AuditStatus.ERROR, e.getMessage(), duration);
            throw e;
        }
    }

    @Transactional
    public void removeServer(String sessionId, String serverConfigName) {
        long start = System.currentTimeMillis();
        log.info("Removing server '{}' from capability registry...", serverConfigName);

        try {
            Optional<McpServerEntity> optServer =
                    serverRepository.findByServerConfigName(serverConfigName);

            if (optServer.isPresent()) {
                McpServerEntity serverEntity = optServer.get();
                UUID serverId = serverEntity.getId();

                for (CapabilityDescriptor desc : registry.getCapabilitiesByServer(serverConfigName)) {
                    auditService.auditRegistryCapabilityRemoved(
                            sessionId, serverConfigName, desc.getPublicName(), desc.getType().name());
                }

                toolRepository.deleteByServerId(serverId);
                resourceRepository.deleteByServerId(serverId);
                promptRepository.deleteByServerId(serverId);

                serverEntity.setStatus("INACTIVE");
                serverRepository.save(serverEntity);

                registry.evictServer(serverConfigName);

                long duration = System.currentTimeMillis() - start;
                log.info("Server '{}' removed from registry ({}ms)", serverConfigName, duration);

                auditService.auditRegistryServerRefresh(
                        sessionId, serverConfigName, AuditStatus.SUCCESS, null, duration);
            } else {
                log.warn("⚠ Server '{}' not found in registry — nothing to remove", serverConfigName);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Failed to remove server '{}': {}", serverConfigName, e.getMessage(), e);
            auditService.auditRegistryServerRefresh(
                    sessionId, serverConfigName, AuditStatus.ERROR, e.getMessage(), duration);
        }
    }

    private String buildPublicName(String serverConfigName, String originalName) {
        return serverConfigName + "_" + originalName;
    }

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
