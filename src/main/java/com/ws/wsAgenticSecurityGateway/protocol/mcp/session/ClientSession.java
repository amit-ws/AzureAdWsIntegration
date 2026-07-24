package com.ws.wsAgenticSecurityGateway.protocol.mcp.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Slf4j
public class ClientSession {

    private final String sessionId;
    private final Instant createdAt;
    private final GatewayAuditService auditService;
    private final AgentRegistryService agentRegistryService;

    private String protocolVersion;
    private McpSchema.ClientCapabilities capabilities;
    private McpSchema.Implementation clientInfo;
    private Map<String, Object> metadata;
    private Map<String, String> tokens;
    private boolean initialized;
    private Instant initializedAt;

    public ClientSession(GatewayAuditService auditService, AgentRegistryService agentRegistryService) {
        this.sessionId = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.auditService = auditService;
        this.agentRegistryService = agentRegistryService;
        this.metadata = new HashMap<>();
        this.tokens = new ConcurrentHashMap<>();
        this.initialized = false;
    }

    public void initialize(
            String protocolVersion,
            McpSchema.ClientCapabilities capabilities,
            McpSchema.Implementation clientInfo,
            Map<String, Object> allData,
            String requestId
    ) {
        this.protocolVersion = protocolVersion;
        this.capabilities = capabilities;
        this.clientInfo = clientInfo;
        this.initialized = true;
        this.initializedAt = Instant.now();

        if (allData != null) {
            this.metadata.putAll(allData);
        }

        logSession();

        try {
            auditService.auditServerSessionInitialized(
                    sessionId,
                    protocolVersion,
                    clientInfo,
                    capabilities,
                    requestId,
                    clientInfo != null ? clientInfo.name() : null
            );
        } catch (Exception e) {
            log.error("Failed to audit session initialization: {}", e.getMessage());
        }

        if (agentRegistryService != null) {
            try {
                JsonNode capsJson = null;
                if (allData != null && !allData.isEmpty()) {
                    capsJson = new ObjectMapper().valueToTree(allData);
                }
                GatewayAgentEntity agent = agentRegistryService.discoverAgent(
                        clientInfo != null ? clientInfo.name() : "unknown",
                        clientInfo != null ? clientInfo.version() : null,
                        protocolVersion,
                        capsJson);
                agentRegistryService.registerSession(agent.getId(), sessionId, null, null);
            } catch (Exception e) {
                log.error("Failed to register agent in registry: {}", e.getMessage());
            }
        }
    }

    private void logSession() {
        log.info("====================================");
 log.info("CLIENT SESSION INITIALIZED");
        log.info("====================================");
        log.info("Session ID: {}", sessionId);
        log.info("Protocol: {}", protocolVersion);
        log.info("Client: {} v{}",
                clientInfo != null ? clientInfo.name() : "unknown",
                clientInfo != null ? clientInfo.version() : "unknown");
        log.info("Connected: {}", createdAt);
        log.info("Initialized: {}", initializedAt);

        if (capabilities != null) {
            log.info("Capabilities: {}", capabilities);
        }

        if (!metadata.isEmpty()) {
            log.info("Metadata: {} entries", metadata.size());
        }

        log.info("====================================");
    }

    public void storeToken(String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            tokens.put(key, value);
 log.debug("Stored agent token: {} ({}...)", key,
                    value.length() > 8 ? value.substring(0, 4) + "..." + value.substring(value.length() - 4) : "***");
        }
    }

    public boolean hasTokens() {
        return tokens != null && !tokens.isEmpty();
    }
}