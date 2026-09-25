package com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.service;

import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.entity.McpServerEntity;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.entity.McpToolEntity;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.repository.McpPromptRepository;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.repository.McpResourceRepository;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.repository.McpServerRepository;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.repository.McpToolRepository;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes the MCP capability ingest → neutral-lookup behavior across the B6 seam: the MCP-typed
 * {@link McpCapabilityRegistrar} maps {@code McpSchema.Tool/Resource/Prompt} into the protocol-neutral
 * {@link CapabilityRegistryService} index, which resolves each by its public name
 * ({@code serverConfigName_originalName}), serves the type-filtered queries, and evicts on removal.
 *
 * <p>Persistence is mocked — this pins the index behavior the spine depends on, independent of the DB. It is
 * the same contract the pre-split {@code CapabilityRegistryService} characterization asserted; only the
 * ingest entry point moved from the registry to the registrar.
 */
class McpCapabilityRegistrarCharacterizationTest {

    private McpServerRepository serverRepository;
    private McpToolRepository toolRepository;
    private McpResourceRepository resourceRepository;
    private McpPromptRepository promptRepository;
    private CapabilityRegistryService registry;
    private McpCapabilityRegistrar registrar;

    private McpServerEntity savedServer;

    @BeforeEach
    void setUp() {
        serverRepository = mock(McpServerRepository.class);
        toolRepository = mock(McpToolRepository.class);
        resourceRepository = mock(McpResourceRepository.class);
        promptRepository = mock(McpPromptRepository.class);
        GatewayAuditService auditService = mock(GatewayAuditService.class);

        when(serverRepository.findByServerConfigName(anyString())).thenReturn(Optional.empty());
        when(serverRepository.saveAndFlush(any())).thenAnswer(inv -> {
            savedServer = inv.getArgument(0);
            if (savedServer.getId() == null) {
                savedServer.setId(UUID.randomUUID());
            }
            return savedServer;
        });

        registry = new CapabilityRegistryService();
        registrar = new McpCapabilityRegistrar(
                serverRepository, toolRepository, resourceRepository, promptRepository, auditService, registry);
    }

    private void ingestSampleServer() {
        List<McpSchema.Tool> tools = List.of(
                new McpSchema.Tool("get_me", "Get the authenticated user", "{\"type\":\"object\"}"));
        List<McpSchema.Resource> resources = List.of(
                McpSchema.Resource.builder()
                        .uri("file://readme").name("readme").description("The readme").mimeType("text/markdown")
                        .build());
        List<McpSchema.Prompt> prompts = List.of(
                new McpSchema.Prompt("greet", "A greeting prompt", List.of()));

        registrar.registerServer("sess-1", "testserver", "Test Server", "1.0", null,
                null, tools, resources, prompts, "tenant-a");
    }

    @Test
    void ingestExposesEachCapabilityByPublicName() {
        ingestSampleServer();

        CapabilityDescriptor tool = registry.lookupByPublicName("testserver_get_me").orElseThrow();
        assertThat(tool.getOriginalName()).isEqualTo("get_me");
        assertThat(tool.getServerConfigName()).isEqualTo("testserver");
        assertThat(tool.getType()).isEqualTo(CapabilityDescriptor.CapabilityType.TOOL);
        assertThat(tool.getDescription()).isEqualTo("Get the authenticated user");

        CapabilityDescriptor resource = registry.lookupByPublicName("testserver_readme").orElseThrow();
        assertThat(resource.getOriginalName()).isEqualTo("readme");
        assertThat(resource.getResourceUri()).isEqualTo("file://readme");
        assertThat(resource.getMimeType()).isEqualTo("text/markdown");
        assertThat(resource.getType()).isEqualTo(CapabilityDescriptor.CapabilityType.RESOURCE);

        CapabilityDescriptor prompt = registry.lookupByPublicName("testserver_greet").orElseThrow();
        assertThat(prompt.getOriginalName()).isEqualTo("greet");
        assertThat(prompt.getType()).isEqualTo(CapabilityDescriptor.CapabilityType.PROMPT);
        assertThat(prompt.getDescription()).isEqualTo("A greeting prompt");
    }

    @Test
    void typeFilteredQueriesAndCountsReflectIngest() {
        ingestSampleServer();

        assertThat(registry.getToolDescriptors()).extracting(CapabilityDescriptor::getPublicName)
                .containsExactly("testserver_get_me");
        assertThat(registry.getResourceDescriptors()).extracting(CapabilityDescriptor::getPublicName)
                .containsExactly("testserver_readme");
        assertThat(registry.getPromptDescriptors()).extracting(CapabilityDescriptor::getPublicName)
                .containsExactly("testserver_greet");
        assertThat(registry.getTotalCapabilityCount()).isEqualTo(3);
        assertThat(registry.getRegisteredServerNames()).contains("testserver");
        assertThat(registry.exists("testserver_get_me")).isTrue();
    }

    @Test
    void removingServerEvictsItsCapabilities() {
        ingestSampleServer();
        when(serverRepository.findByServerConfigName("testserver")).thenReturn(Optional.of(savedServer));

        registrar.removeServer("sess-1", "testserver");

        assertThat(registry.lookupByPublicName("testserver_get_me")).isEmpty();
        assertThat(registry.getTotalCapabilityCount()).isZero();
        assertThat(registry.getRegisteredServerNames()).doesNotContain("testserver");
    }

    @Test
    void reRegisteringSameServerReplacesStaleCapabilities() {
        registrar.registerServer("sess-1", "testserver", "Test", "1.0", null, null,
                List.of(new McpSchema.Tool("get_me", "Get me", "{\"type\":\"object\"}"),
                        new McpSchema.Tool("doomed", "Removed on re-register", "{\"type\":\"object\"}")),
                List.of(), List.of(), "tenant-a");
        assertThat(registry.exists("testserver_doomed")).isTrue();

        // Re-register the same server with only one tool — the upsert path returns the saved server.
        when(serverRepository.findByServerConfigName("testserver")).thenReturn(Optional.of(savedServer));
        registrar.registerServer("sess-1", "testserver", "Test", "1.0", null, null,
                List.of(new McpSchema.Tool("get_me", "Get me", "{\"type\":\"object\"}")),
                List.of(), List.of(), "tenant-a");

        assertThat(registry.exists("testserver_get_me")).isTrue();
        assertThat(registry.exists("testserver_doomed")).isFalse();   // stale entry evicted by replace
        assertThat(registry.getTotalCapabilityCount()).isEqualTo(1);
    }

    @Test
    void loadFromDatabaseRestoresCapabilitiesIntoRegistry() {
        McpServerEntity server = McpServerEntity.builder()
                .serverConfigName("dbserver").status("ACTIVE").build();
        server.setId(UUID.randomUUID());
        McpToolEntity tool = McpToolEntity.builder()
                .server(server).toolName("db_tool").publicName("dbserver_db_tool").description("From DB")
                .build();

        when(serverRepository.findByStatus("ACTIVE")).thenReturn(List.of(server));
        when(toolRepository.findByServerId(server.getId())).thenReturn(List.of(tool));
        when(resourceRepository.findByServerId(server.getId())).thenReturn(List.of());
        when(promptRepository.findByServerId(server.getId())).thenReturn(List.of());

        registrar.loadFromDatabase();

        CapabilityDescriptor restored = registry.lookupByPublicName("dbserver_db_tool").orElseThrow();
        assertThat(restored.getOriginalName()).isEqualTo("db_tool");
        assertThat(restored.getServerConfigName()).isEqualTo("dbserver");
        assertThat(restored.getType()).isEqualTo(CapabilityDescriptor.CapabilityType.TOOL);
        assertThat(restored.getDescription()).isEqualTo("From DB");
        assertThat(registry.getRegisteredServerNames()).contains("dbserver");
    }
}
