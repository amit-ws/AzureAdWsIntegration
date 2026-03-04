package com.ws.wsAgenticSecurityGateway.wsClient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpServerConfig;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSession;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSessionManager;
import com.ws.wsAgenticSecurityGateway.wsClient.dto.ServerConfigRequest;
import com.ws.wsAgenticSecurityGateway.wsClient.dto.ServerConfigResponse;
import com.ws.wsAgenticSecurityGateway.wsClient.entity.GatewayServerConfigEntity;
import com.ws.wsAgenticSecurityGateway.wsClient.repository.GatewayServerConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core service for MCP server configuration management.
 *
 * <p>Provides CRUD operations for server configs persisted in the
 * {@code gateway_server_config} table, plus connection lifecycle
 * operations (connect, disconnect, reconnect).
 *
 * <p>Header values containing {@code ${env:VAR_NAME}} patterns are
 * resolved from system environment variables at connect time.
 */
@Service
@Slf4j
public class ServerConfigService {

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{env:([^}]+)}");
    private static final Set<String> SECRET_HEADER_KEYWORDS = Set.of(
            "auth", "authorization", "token", "secret", "key", "password",
            "bearer", "credential", "api-key", "apikey", "cookie", "session"
    );
    private static final String REDACTED_MARKER = "***REDACTED***";

    private final GatewayServerConfigRepository configRepository;
    private final McpSessionManager sessionManager;
    private final McpAuditService auditService;
    private final ServerConfigCryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public ServerConfigService(GatewayServerConfigRepository configRepository,
                               McpSessionManager sessionManager,
                               McpAuditService auditService,
                               ServerConfigCryptoService cryptoService,
                               ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.sessionManager = sessionManager;
        this.auditService = auditService;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CRUD
    // ════════════════════════════════════════════════════════════════════

    /**
     * Create a new server configuration. Auto-connects if enabled + autoConnect.
     */
    @Transactional
    public ServerConfigResponse createServerConfig(ServerConfigRequest request) {
        // Validate uniqueness
        if (configRepository.existsByServerName(request.getServerName())) {
            throw new IllegalArgumentException(
                    "Server config '" + request.getServerName() + "' already exists");
        }

        // Persist
        GatewayServerConfigEntity entity = GatewayServerConfigEntity.builder()
                .serverName(request.getServerName())
                .type(request.getType() != null ? request.getType() : "http")
                .url(request.getUrl())
                .headers(toJsonNode(prepareHeadersForStorage(request.getHeaders(), null)))
                .serverConfig(toJsonNode(request.getServerConfig()))
                .timeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 30)
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .autoConnect(request.getAutoConnect() != null ? request.getAutoConnect() : true)
                .build();

        entity = configRepository.save(entity);
        log.info("Server config '{}' created (url={})", entity.getServerName(), entity.getUrl());

        // Audit
        auditService.auditServerConfigCreated(entity.getServerName(), entity.getUrl());

        // Auto-connect if enabled
        if (Boolean.TRUE.equals(entity.getEnabled()) && Boolean.TRUE.equals(entity.getAutoConnect())) {
            try {
                connectFromConfig(entity);
                log.info("Server '{}' auto-connected after creation", entity.getServerName());
            } catch (Exception e) {
                log.warn("Auto-connect failed for '{}' after creation: {}",
                        entity.getServerName(), e.getMessage());
                // Don't throw — config is saved, connection can be retried manually
            }
        }

        return toResponse(entity);
    }

    /**
     * List all server configurations with enriched connection status.
     */
    @Transactional(readOnly = true)
    public List<ServerConfigResponse> listServerConfigs() {
        List<GatewayServerConfigEntity> entities = configRepository.findAllByOrderByServerNameAsc();
        List<ServerConfigResponse> responses = new ArrayList<>();
        for (GatewayServerConfigEntity entity : entities) {
            responses.add(toResponse(entity));
        }
        return responses;
    }

    /**
     * Get a specific server configuration by name.
     */
    @Transactional(readOnly = true)
    public ServerConfigResponse getServerConfig(String serverName) {
        GatewayServerConfigEntity entity = configRepository.findByServerName(serverName)
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));
        return toResponse(entity);
    }

    /**
     * Update an existing server configuration.
     * If the server was connected, disconnects first, updates, then reconnects.
     */
    @Transactional
    public ServerConfigResponse updateServerConfig(String serverName, ServerConfigRequest request) {
        GatewayServerConfigEntity entity = configRepository.findByServerName(serverName)
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));

        boolean wasConnected = sessionManager.isConnected(serverName);

        // Disconnect first if connected
        if (wasConnected) {
            try {
                sessionManager.disconnect(serverName);
                log.info("Server '{}' disconnected before config update", serverName);
            } catch (Exception e) {
                log.warn("Error disconnecting '{}' before update: {}", serverName, e.getMessage());
            }
        }

        // Update entity fields
        if (request.getUrl() != null) entity.setUrl(request.getUrl());
        if (request.getType() != null) entity.setType(request.getType());
        if (request.getHeaders() != null) {
            Map<String, String> existingStoredHeaders = jsonNodeToStringMap(entity.getHeaders());
            Map<String, String> headersToStore =
                    prepareHeadersForStorage(request.getHeaders(), existingStoredHeaders);
            entity.setHeaders(toJsonNode(headersToStore));
        }
        if (request.getServerConfig() != null) entity.setServerConfig(toJsonNode(request.getServerConfig()));
        if (request.getTimeoutSeconds() != null) entity.setTimeoutSeconds(request.getTimeoutSeconds());
        if (request.getEnabled() != null) entity.setEnabled(request.getEnabled());
        if (request.getAutoConnect() != null) entity.setAutoConnect(request.getAutoConnect());

        entity = configRepository.save(entity);
        log.info("Server config '{}' updated", serverName);

        // Audit
        auditService.auditServerConfigUpdated(serverName, entity.getUrl());

        // Reconnect if it was connected and still enabled
        if (wasConnected && Boolean.TRUE.equals(entity.getEnabled())) {
            try {
                connectFromConfig(entity);
                log.info("Server '{}' reconnected after config update", serverName);
            } catch (Exception e) {
                log.warn("Reconnect failed for '{}' after update: {}", serverName, e.getMessage());
            }
        }

        return toResponse(entity);
    }

    /**
     * Delete a server configuration. Disconnects first if connected.
     */
    @Transactional
    public void deleteServerConfig(String serverName) {
        GatewayServerConfigEntity entity = configRepository.findByServerName(serverName)
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));

        // Disconnect if connected
        if (sessionManager.isConnected(serverName)) {
            try {
                sessionManager.disconnect(serverName);
                log.info("Server '{}' disconnected before deletion", serverName);
            } catch (Exception e) {
                log.warn("Error disconnecting '{}' before deletion: {}", serverName, e.getMessage());
            }
        }

        configRepository.delete(entity);
        log.info("Server config '{}' deleted", serverName);

        // Audit
        auditService.auditServerConfigDeleted(serverName);
    }

    // ════════════════════════════════════════════════════════════════════
    //  CONNECTION LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    /**
     * Connect a server by name. Must be enabled.
     */
    public void connectServer(String serverName) {
        GatewayServerConfigEntity entity = configRepository.findByServerName(serverName)
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));

        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalStateException("Server '" + serverName + "' is disabled. Enable it first.");
        }

        connectFromConfig(entity);
    }

    /**
     * Disconnect a server by name.
     */
    public void disconnectServer(String serverName) {
        // Verify config exists
        configRepository.findByServerName(serverName)
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));

        if (!sessionManager.isConnected(serverName)) {
            throw new IllegalStateException("Server '" + serverName + "' is not connected");
        }

        sessionManager.disconnect(serverName);
    }

    /**
     * Reconnect a server — disconnect then connect again.
     */
    public void reconnectServer(String serverName) {
        GatewayServerConfigEntity entity = configRepository.findByServerName(serverName)
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));

        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalStateException("Server '" + serverName + "' is disabled. Enable it first.");
        }

        // Disconnect if currently connected
        if (sessionManager.isConnected(serverName)) {
            try {
                sessionManager.disconnect(serverName);
            } catch (Exception e) {
                log.warn("Error disconnecting '{}' before reconnect: {}", serverName, e.getMessage());
            }
        }

        connectFromConfig(entity);
    }

    /**
     * Returns all configs that should auto-connect on startup.
     */
    @Transactional(readOnly = true)
    public List<GatewayServerConfigEntity> getStartupConfigs() {
        return configRepository.findByEnabledTrueAndAutoConnectTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    //  CORE — Build McpServerConfig from entity and connect
    // ════════════════════════════════════════════════════════════════════

    /**
     * Convert entity to McpServerConfig (resolving env vars) and connect.
     */
    public void connectFromConfig(GatewayServerConfigEntity entity) {
        // Convert JSONB headers to runtime-ready headers (decrypt + resolve env vars)
        Map<String, String> storedHeaders = jsonNodeToStringMap(entity.getHeaders());
        Map<String, String> resolvedHeaders = buildRuntimeHeaders(storedHeaders);

        // Convert JSONB serverConfig to Map<String,Object>
        Map<String, Object> config = jsonNodeToObjectMap(entity.getServerConfig());

        // Build McpServerConfig
        McpServerConfig mcpConfig = new McpServerConfig();
        mcpConfig.setType(entity.getType());
        mcpConfig.setUrl(entity.getUrl());
        mcpConfig.setHeaders(resolvedHeaders);
        mcpConfig.setConfig(config);
        mcpConfig.setTimeout(entity.getTimeoutSeconds());

        try {
            sessionManager.connect(entity.getServerName(), mcpConfig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect server '" + entity.getServerName() + "': " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  ENV VAR RESOLUTION
    // ════════════════════════════════════════════════════════════════════

    /**
     * Resolve {@code ${env:VAR_NAME}} patterns in header values.
     * If an env var is not found, the literal pattern is kept (connection will likely fail).
     */
    private Map<String, String> resolveEnvVars(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.contains("${env:")) {
                StringBuffer sb = new StringBuffer();
                Matcher matcher = ENV_VAR_PATTERN.matcher(value);
                while (matcher.find()) {
                    String varName = matcher.group(1);
                    String envValue = System.getenv(varName);
                    if (envValue != null) {
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(envValue));
                        log.debug("Resolved env var '{}' for header '{}'", varName, entry.getKey());
                    } else {
                        log.warn("Environment variable '{}' not found for header '{}' — keeping literal",
                                varName, entry.getKey());
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                    }
                }
                matcher.appendTail(sb);
                resolved.put(entry.getKey(), sb.toString());
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    /**
     * Build runtime headers from stored headers by decrypting encrypted values first,
     * then resolving ${env:VAR} placeholders.
     */
    private Map<String, String> buildRuntimeHeaders(Map<String, String> storedHeaders) {
        if (storedHeaders == null || storedHeaders.isEmpty()) {
            return storedHeaders;
        }

        Map<String, String> decrypted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : storedHeaders.entrySet()) {
            try {
                decrypted.put(entry.getKey(), cryptoService.decryptIfEncrypted(entry.getValue()));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to decrypt header '" + entry.getKey() + "' for server config runtime use", e);
            }
        }
        return resolveEnvVars(decrypted);
    }

    // ════════════════════════════════════════════════════════════════════
    //  RESPONSE BUILDING + SECRET MASKING
    // ════════════════════════════════════════════════════════════════════

    private ServerConfigResponse toResponse(GatewayServerConfigEntity entity) {
        // Decrypt (if needed) + mask secrets in response headers
        Map<String, String> storedHeaders = jsonNodeToStringMap(entity.getHeaders());
        Map<String, String> maskedHeaders = maskHeadersForResponse(storedHeaders);
        Map<String, Object> serverConfigMap = jsonNodeToObjectMap(entity.getServerConfig());

        // Enrich with live connection status
        boolean connected = sessionManager.isConnected(entity.getServerName());
        String connectionSessionId = null;
        java.time.LocalDateTime connectedAt = null;
        int toolCount = 0;
        int resourceCount = 0;
        int promptCount = 0;

        if (connected) {
            try {
                McpSession session = sessionManager.getSession(entity.getServerName());
                connectionSessionId = session.getSessionId();
                connectedAt = session.getConnectedAt();
                toolCount = session.getTools() != null ? session.getTools().size() : 0;
                resourceCount = session.getResources() != null ? session.getResources().size() : 0;
                promptCount = session.getPrompts() != null ? session.getPrompts().size() : 0;
            } catch (Exception e) {
                log.warn("Could not enrich session data for '{}': {}", entity.getServerName(), e.getMessage());
            }
        }

        return ServerConfigResponse.builder()
                .id(entity.getId())
                .serverName(entity.getServerName())
                .type(entity.getType())
                .url(entity.getUrl())
                .headers(maskedHeaders)
                .serverConfig(serverConfigMap)
                .timeoutSeconds(entity.getTimeoutSeconds())
                .enabled(entity.getEnabled())
                .autoConnect(entity.getAutoConnect())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .connected(connected)
                .connectionSessionId(connectionSessionId)
                .connectedAt(connectedAt)
                .toolCount(toolCount)
                .resourceCount(resourceCount)
                .promptCount(promptCount)
                .build();
    }

    /**
     * For storage:
     * - Keep existing encrypted secret if UI sends masked placeholder on update.
     * - Encrypt secret header values at rest.
     * - Keep env placeholders (${env:...}) as-is.
     */
    private Map<String, String> prepareHeadersForStorage(Map<String, String> incomingHeaders,
                                                         Map<String, String> existingStoredHeaders) {
        if (incomingHeaders == null) return null;

        Map<String, String> headersToStore = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : incomingHeaders.entrySet()) {
            String key = entry.getKey();
            String incomingValue = entry.getValue();

            if (incomingValue == null) {
                headersToStore.put(key, null);
                continue;
            }

            String existingStoredValue = existingStoredHeaders != null
                    ? existingStoredHeaders.get(key)
                    : null;

            // UI sends masked placeholders for unchanged secrets; preserve stored value.
            if (isMaskedPlaceholder(incomingValue) && existingStoredValue != null) {
                if (cryptoService.isEncryptedValue(existingStoredValue) || isSecretHeaderKey(key)) {
                    headersToStore.put(key, existingStoredValue);
                    continue;
                }
            }

            if (isSecretHeaderKey(key) && !containsEnvPlaceholder(incomingValue)) {
                headersToStore.put(key, cryptoService.encrypt(incomingValue.trim()));
            } else {
                headersToStore.put(key, incomingValue.trim());
            }
        }
        return headersToStore;
    }

    /**
     * For API responses:
     * - Decrypt encrypted values.
     * - Mask secret values except ${env:...} placeholders.
     */
    private Map<String, String> maskHeadersForResponse(Map<String, String> storedHeaders) {
        if (storedHeaders == null) return null;

        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : storedHeaders.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            String plainValue;
            try {
                plainValue = cryptoService.decryptIfEncrypted(value);
            } catch (Exception e) {
                log.warn("Failed to decrypt header '{}' for response masking: {}", key, e.getMessage());
                plainValue = null;
            }

            if (isSecretHeaderKey(key) && plainValue != null && !containsEnvPlaceholder(plainValue)) {
                masked.put(key, maskValue(plainValue));
            } else {
                masked.put(key, plainValue);
            }
        }
        return masked;
    }

    private boolean isSecretHeaderKey(String headerKey) {
        if (headerKey == null) return false;
        String keyLower = headerKey.toLowerCase(Locale.ROOT);
        return SECRET_HEADER_KEYWORDS.stream().anyMatch(keyLower::contains);
    }

    private boolean isMaskedPlaceholder(String value) {
        return value != null && value.contains(REDACTED_MARKER);
    }

    private boolean containsEnvPlaceholder(String value) {
        return value != null && value.contains("${env:");
    }

    private String maskValue(String value) {
        if (value == null) return null;
        if (value.length() <= 8) return "***REDACTED***";
        return value.substring(0, 4) + "***REDACTED***" + value.substring(value.length() - 4);
    }

    // ════════════════════════════════════════════════════════════════════
    //  JSON HELPERS
    // ════════════════════════════════════════════════════════════════════

    private JsonNode toJsonNode(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.valueToTree(value);
        } catch (Exception e) {
            log.warn("Failed to convert to JsonNode: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> jsonNodeToStringMap(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            Map<String, String> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry ->
                    map.put(entry.getKey(), entry.getValue().asText()));
            return map;
        } catch (Exception e) {
            log.warn("Failed to convert JsonNode to Map<String,String>: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonNodeToObjectMap(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            log.warn("Failed to convert JsonNode to Map<String,Object>: {}", e.getMessage());
            return null;
        }
    }
}
