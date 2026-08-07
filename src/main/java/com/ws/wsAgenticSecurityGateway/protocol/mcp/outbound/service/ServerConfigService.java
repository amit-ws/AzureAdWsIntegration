package com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.service;
import com.ws.wsAgenticSecurityGateway.common.crypto.SecretCryptoService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config.McpServerConfig;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config.McpSession;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config.McpSessionManager;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.dto.ServerConfigRequest;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.dto.ServerConfigResponse;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.dto.ServerConfigTestResponse;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.entity.GatewayServerConfigEntity;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.repository.GatewayServerConfigRepository;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final GatewayAuditService auditService;
    private final SecretCryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public ServerConfigService(GatewayServerConfigRepository configRepository,
                               McpSessionManager sessionManager,
                               GatewayAuditService auditService,
                               SecretCryptoService cryptoService,
                               ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.sessionManager = sessionManager;
        this.auditService = auditService;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ServerConfigResponse createServerConfig(ServerConfigRequest request) {
        String tenant = TenantContext.get();
        if (configRepository.existsByServerNameAndWsTenantName(request.getServerName(), tenant)) {
            throw new IllegalArgumentException(
                    "Server config '" + request.getServerName() + "' already exists");
        }

        GatewayServerConfigEntity entity = GatewayServerConfigEntity.builder()
                .serverName(request.getServerName())
                .type(request.getType() != null ? request.getType() : "http")
                .url(request.getUrl())
                .headers(toJsonNode(prepareHeadersForStorage(request.getHeaders(), null)))
                .serverConfig(toJsonNode(request.getServerConfig()))
                .timeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 30)
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .autoConnect(request.getAutoConnect() != null ? request.getAutoConnect() : true)
                .wsTenantName(tenant)
                .build();

        entity = configRepository.save(entity);
        log.info("Server config '{}' created (url={})", entity.getServerName(), entity.getUrl());

        auditService.auditServerConfigCreated(entity.getServerName(), entity.getUrl());

        if (Boolean.TRUE.equals(entity.getEnabled()) && Boolean.TRUE.equals(entity.getAutoConnect())) {
            try {
                connectFromConfig(entity);
                log.info("Server '{}' auto-connected after creation", entity.getServerName());
            } catch (Exception e) {
                log.warn("Auto-connect failed for '{}' after creation: {}",
                        entity.getServerName(), e.getMessage());
            }
        }

        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public List<ServerConfigResponse> listServerConfigs() {
        List<GatewayServerConfigEntity> entities = configRepository.findAllByWsTenantNameOrderByServerNameAsc(TenantContext.get());
        List<ServerConfigResponse> responses = new ArrayList<>();
        for (GatewayServerConfigEntity entity : entities) {
            responses.add(toResponse(entity));
        }
        return responses;
    }

    @Transactional(readOnly = true)
    public ServerConfigResponse getServerConfig(String serverName) {
        GatewayServerConfigEntity entity = configRepository.findByServerNameAndWsTenantName(serverName, TenantContext.get())
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));
        return toResponse(entity);
    }

    @Transactional
    public ServerConfigResponse updateServerConfig(String serverName, ServerConfigRequest request) {
        GatewayServerConfigEntity entity = configRepository.findByServerNameAndWsTenantName(serverName, TenantContext.get())
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));

        boolean wasConnected = sessionManager.isConnected(serverName);

        if (wasConnected) {
            try {
                sessionManager.disconnect(serverName);
                log.info("Server '{}' disconnected before config update", serverName);
            } catch (Exception e) {
                log.warn("Error disconnecting '{}' before update: {}", serverName, e.getMessage());
            }
        }

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

        auditService.auditServerConfigUpdated(serverName, entity.getUrl());

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

    @Transactional
    public void deleteServerConfig(String serverName) {
        GatewayServerConfigEntity entity = configRepository.findByServerNameAndWsTenantName(serverName, TenantContext.get())
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));

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

        auditService.auditServerConfigDeleted(serverName);
    }

    public void connectServer(String serverName) {
        GatewayServerConfigEntity entity = configRepository.findByServerNameAndWsTenantName(serverName, TenantContext.get())
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));

        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalStateException("Server '" + serverName + "' is disabled. Enable it first.");
        }

        connectFromConfig(entity);
    }

    public void disconnectServer(String serverName) {
        configRepository.findByServerNameAndWsTenantName(serverName, TenantContext.get())
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));

        if (!sessionManager.isConnected(serverName)) {
            throw new IllegalStateException("Server '" + serverName + "' is not connected");
        }

        sessionManager.disconnect(serverName);
    }

    public void reconnectServer(String serverName) {
        GatewayServerConfigEntity entity = configRepository.findByServerNameAndWsTenantName(serverName, TenantContext.get())
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));

        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalStateException("Server '" + serverName + "' is disabled. Enable it first.");
        }

        if (sessionManager.isConnected(serverName)) {
            try {
                sessionManager.disconnect(serverName);
            } catch (Exception e) {
                log.warn("Error disconnecting '{}' before reconnect: {}", serverName, e.getMessage());
            }
        }

        connectFromConfig(entity);
    }

    @Transactional(readOnly = true)
    public List<GatewayServerConfigEntity> getStartupConfigs() {
        return configRepository.findByEnabledTrueAndAutoConnectTrue();
    }

    /**
     * Dry-run "Test Connection" for an UNSAVED config (the add/edit form). Probes the given URL + headers
     * without persisting anything. Env-var placeholders are resolved; a still-masked secret is rejected up front
     * (testing with a redacted value is meaningless — the caller should re-enter it or test the saved server).
     * Not {@code @Transactional} — it does no DB work and must not hold a connection across the network probe.
     */
    public ServerConfigTestResponse testConnection(ServerConfigRequest request) {
        Map<String, String> headers = resolveEnvVars(request.getHeaders());
        assertNoMaskedSecret(headers);

        McpServerConfig cfg = new McpServerConfig();
        cfg.setType(request.getType() != null ? request.getType() : "http");
        cfg.setUrl(request.getUrl());
        cfg.setHeaders(headers);
        cfg.setConfig(request.getServerConfig());
        cfg.setTimeout(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 30);
        return runProbe(request.getServerName(), request.getUrl(), cfg);
    }

    /**
     * Dry-run "Test Connection" for a SAVED config (re-validate an existing server). Loads the tenant-scoped
     * config, decrypts its stored secret headers + resolves env vars, and probes. Reads the entity in its own
     * short transaction, then probes outside any transaction.
     */
    public ServerConfigTestResponse testConnection(String serverName) {
        GatewayServerConfigEntity entity = loadForTenant(serverName);
        Map<String, String> resolvedHeaders = buildRuntimeHeaders(jsonNodeToStringMap(entity.getHeaders()));

        McpServerConfig cfg = new McpServerConfig();
        cfg.setType(entity.getType());
        cfg.setUrl(entity.getUrl());
        cfg.setHeaders(resolvedHeaders);
        cfg.setConfig(jsonNodeToObjectMap(entity.getServerConfig()));
        cfg.setTimeout(entity.getTimeoutSeconds());
        return runProbe(serverName, entity.getUrl(), cfg);
    }

    /**
     * Flip a server's {@code enabled} flag without a full edit. Disabling a currently-connected server also
     * disconnects it (a disabled server must not keep a live session); enabling does NOT auto-connect — the
     * admin drives the explicit connect. No-op if already in the requested state.
     */
    @Transactional
    public ServerConfigResponse setEnabled(String serverName, boolean enabled) {
        GatewayServerConfigEntity entity = loadForTenant(serverName);

        if (Boolean.valueOf(enabled).equals(entity.getEnabled())) {
            return toResponse(entity);
        }

        if (!enabled && sessionManager.isConnected(serverName)) {
            try {
                sessionManager.disconnect(serverName);
                log.info("Server '{}' disconnected because it was disabled", serverName);
            } catch (Exception e) {
                log.warn("Error disconnecting '{}' while disabling: {}", serverName, e.getMessage());
            }
        }

        entity.setEnabled(enabled);
        entity = configRepository.save(entity);
        auditService.auditServerConfigUpdated(serverName, entity.getUrl());
        log.info("Server config '{}' {}", serverName, enabled ? "enabled" : "disabled");
        return toResponse(entity);
    }

    private GatewayServerConfigEntity loadForTenant(String serverName) {
        return configRepository.findByServerNameAndWsTenantName(serverName, TenantContext.get())
                .orElseThrow(() -> new NoSuchElementException(
                        "Server config '" + serverName + "' not found"));
    }

    private ServerConfigTestResponse runProbe(String serverName, String url, McpServerConfig cfg) {
        try {
            McpSessionManager.ProbeResult r = sessionManager.probe(cfg);
            return ServerConfigTestResponse.builder()
                    .ok(true)
                    .serverName(serverName)
                    .url(url)
                    .latencyMs(r.latencyMs())
                    .serverInfoName(r.serverInfoName())
                    .serverInfoVersion(r.serverInfoVersion())
                    .toolCount(r.toolCount())
                    .resourceCount(r.resourceCount())
                    .promptCount(r.promptCount())
                    .build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.info("Test-connection failed for '{}' ({}): {}", serverName, url, msg);
            return ServerConfigTestResponse.builder()
                    .ok(false)
                    .serverName(serverName)
                    .url(url)
                    .error(msg)
                    .build();
        }
    }

    private void assertNoMaskedSecret(Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (isMaskedPlaceholder(entry.getValue())) {
                throw new IllegalArgumentException(
                        "Header '" + entry.getKey() + "' still holds a masked secret. Re-enter its real value to "
                                + "test, or use Test on the saved server.");
            }
        }
    }

    public void connectFromConfig(GatewayServerConfigEntity entity) {
        Map<String, String> storedHeaders = jsonNodeToStringMap(entity.getHeaders());
        Map<String, String> resolvedHeaders = buildRuntimeHeaders(storedHeaders);

        Map<String, Object> config = jsonNodeToObjectMap(entity.getServerConfig());

        McpServerConfig mcpConfig = new McpServerConfig();
        mcpConfig.setType(entity.getType());
        mcpConfig.setUrl(entity.getUrl());
        mcpConfig.setHeaders(resolvedHeaders);
        mcpConfig.setConfig(config);
        mcpConfig.setTimeout(entity.getTimeoutSeconds());

        try {
            sessionManager.connect(entity.getServerName(), mcpConfig, entity.getWsTenantName());
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect server '" + entity.getServerName() + "': " + e.getMessage(), e);
        }
    }

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

    private Map<String, String> buildRuntimeHeaders(Map<String, String> storedHeaders) {
        if (storedHeaders == null || storedHeaders.isEmpty()) {
            return storedHeaders;
        }

        Map<String, String> decrypted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : storedHeaders.entrySet()) {
            try {
                String decryptedValue = cryptoService.decryptIfEncrypted(entry.getValue());
                log.debug("Header '{}': stored={}... decrypted={}...",
                        entry.getKey(),
                        entry.getValue().substring(0, Math.min(20, entry.getValue().length())),
                        decryptedValue.substring(0, Math.min(20, decryptedValue.length())));
                decrypted.put(entry.getKey(), decryptedValue);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to decrypt header '" + entry.getKey() + "' for server config runtime use", e);
            }
        }
        return resolveEnvVars(decrypted);
    }

    private ServerConfigResponse toResponse(GatewayServerConfigEntity entity) {
        Map<String, String> storedHeaders = jsonNodeToStringMap(entity.getHeaders());
        Map<String, String> maskedHeaders = maskHeadersForResponse(storedHeaders);
        Map<String, Object> serverConfigMap = jsonNodeToObjectMap(entity.getServerConfig());

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
            if (existingStoredValue == null && existingStoredHeaders != null) {
                existingStoredValue = findHeaderValueIgnoreCase(existingStoredHeaders, key);
            }

            if (isMaskedPlaceholder(incomingValue)) {
                if (existingStoredValue != null &&
                        (cryptoService.isEncryptedValue(existingStoredValue) || isSecretHeaderKey(key))) {
                    headersToStore.put(key, existingStoredValue);
                    continue;
                }
                throw new IllegalArgumentException(
                        "Masked value received for header '" + key + "' but no existing secret is available. "
                                + "Resubmit the real value.");
            }

            if (isSecretHeaderKey(key) && !containsEnvPlaceholder(incomingValue)) {
                headersToStore.put(key, cryptoService.encrypt(incomingValue.trim()));
            } else {
                headersToStore.put(key, incomingValue.trim());
            }
        }
        return headersToStore;
    }

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

    private String findHeaderValueIgnoreCase(Map<String, String> headers, String requestedKey) {
        if (headers == null || requestedKey == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (requestedKey.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

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
