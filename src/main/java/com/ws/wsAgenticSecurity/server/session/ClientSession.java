package com.ws.wsAgenticSecurity.server.session;

import com.ws.wsAgenticSecurity.audit.service.McpAuditService;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Slf4j
public class ClientSession {

    private final String sessionId;
    private final Instant createdAt;
    private final McpAuditService auditService;

    private String protocolVersion;
    private McpSchema.ClientCapabilities capabilities;
    private McpSchema.Implementation clientInfo;
    private Map<String, Object> metadata;
    private Map<String, String> tokens;
    private boolean initialized;
    private Instant initializedAt;

    public ClientSession(McpAuditService auditService) {
        this.sessionId = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.auditService = auditService;
        this.metadata = new HashMap<>();
        this.tokens = new HashMap<>();
        this.initialized = false;
    }

    public void initialize(
            String protocolVersion,
            McpSchema.ClientCapabilities capabilities,
            McpSchema.Implementation clientInfo,
            Map<String, Object> allData
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

        // Audit — session initialization
        try {
            auditService.auditServerSessionInitialized(
                    sessionId,
                    protocolVersion,
                    clientInfo,
                    capabilities
            );
        } catch (Exception e) {
            log.error("Failed to audit session initialization: {}", e.getMessage());
        }
    }

    private void logSession() {
        log.info("====================================");
        log.info("📊 CLIENT SESSION INITIALIZED");
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
}