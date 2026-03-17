package com.ws.wsAgenticSecurityGateway.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.entity.PdpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.error.McpAuditException;
import com.ws.wsAgenticSecurityGateway.audit.error.McpErrorCode;
import com.ws.wsAgenticSecurityGateway.audit.repository.McpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asynchronous audit-logging service for the WS MCP Gateway.
 *
 * <p>
 * Every public method is {@code @Async} and runs on the dedicated
 * {@code mcpAuditExecutor} thread pool so that audit persistence
 * <strong>never</strong> blocks the request hot path.
 *
 * <h3>Covered Activity Areas</h3>
 * <ol>
 * <li>AI Client &lt;-&gt; WS Server (Northbound) → {@code mcp_audit_log}</li>
 * <li>WS Client &lt;-&gt; Enterprise MCP Server (Southbound) →
 * {@code mcp_audit_log}</li>
 * <li>Capability Registry CRUD → {@code mcp_audit_log}</li>
 * <li>Orchestration Layer → {@code mcp_audit_log}</li>
 * <li>PDP (Policy Decision Point) → {@code pdp_audit_log} (separate table)</li>
 * </ol>
 *
 * <p>
 * Areas 1–4 persist to {@code mcp_audit_log}. Area 5 persists to
 * {@code pdp_audit_log}. Both tables are linked by {@code correlation_id}.
 */
@Service
@Slf4j
public class McpAuditService {

        private final McpAuditLogRepository repository;
        private final PdpAuditLogRepository pdpRepository;
        private final ObjectMapper objectMapper;

        public McpAuditService(McpAuditLogRepository repository,
                        PdpAuditLogRepository pdpRepository) {
                this.repository = repository;
                this.pdpRepository = pdpRepository;
                this.objectMapper = new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 2 — WS Client <-> Enterprise MCP Server (Southbound)
        // ════════════════════════════════════════════════════════════════════

        /**
         * Audit: client successfully initialized a session with an enterprise MCP
         * server.
         */
        @Async("mcpAuditExecutor")
        public void auditClientSessionInitialized(String sessionId,
                        String serverName,
                        String protocolVersion,
                        Map<String, Object> serverInfo,
                        Map<String, Object> capabilities,
                        long durationMs) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_SESSION_INITIALIZED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .mcpMethod("initialize")
                                .protocolVersion(protocolVersion)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of("serverName", serverName)))
                                .responsePayload(toJson(Map.of(
                                                "serverInfo", serverInfo != null ? serverInfo : Map.of(),
                                                "capabilities", capabilities != null ? capabilities : Map.of())))
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: client session initialization failed.
         */
        @Async("mcpAuditExecutor")
        public void auditClientSessionInitFailed(String sessionId,
                        String serverName,
                        String errorMessage,
                        long durationMs) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_SESSION_INITIALIZED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .mcpMethod("initialize")
                                .correlationId(generateCorrelationId())
                                .errorCode(McpErrorCode.TRANSPORT_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: client disconnected from an enterprise MCP server.
         */
        @Async("mcpAuditExecutor")
        public void auditClientSessionDisconnected(String sessionId,
                        String serverName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_SESSION_DISCONNECTED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        /**
         * Synchronous variant of {@link #auditClientSessionDisconnected} — used in
         * shutdown/cleanup paths where @Async would race with DataSource destruction.
         * <p>
         * NOT annotated with @Async so the DB write completes before the caller
         * returns.
         */
        public void auditClientSessionDisconnectedSync(String sessionId,
                        String serverName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_SESSION_DISCONNECTED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        /**
         * Audit: southbound health check failed for an enterprise MCP server.
         */
        @Async("mcpAuditExecutor")
        public void auditClientHealthCheckFailed(String sessionId,
                        String serverName,
                        int consecutiveFailures,
                        String reason) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_HEALTH_CHECK_FAILED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.FAILURE)
                                .severity(consecutiveFailures >= 3 ? AuditSeverity.ERROR : AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .errorMessage("Health check failed (attempt " + consecutiveFailures + "): " + reason)
                                .build());
        }

        /**
         * Audit: tools list fetched from enterprise MCP server.
         */
        @Async("mcpAuditExecutor")
        public void auditClientToolsListFetched(String sessionId,
                        String serverName,
                        int toolCount,
                        Object toolsList,
                        long durationMs) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_TOOLS_LIST_FETCHED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .mcpMethod("tools/list")
                                .capabilityType("TOOL")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of(
                                                "toolCount", toolCount,
                                                "tools", toolsList != null ? toolsList : "[]")))
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: tools list fetch failed.
         */
        @Async("mcpAuditExecutor")
        public void auditClientToolsListFailed(String sessionId,
                        String serverName,
                        String errorMessage,
                        long durationMs) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_TOOLS_LIST_FETCHED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .mcpMethod("tools/list")
                                .capabilityType("TOOL")
                                .correlationId(generateCorrelationId())
                                .errorCode(McpErrorCode.INTERNAL_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: resources list fetched from enterprise MCP server.
         */
        @Async("mcpAuditExecutor")
        public void auditClientResourcesListFetched(String sessionId,
                        String serverName,
                        int resourceCount,
                        Object resourcesList,
                        long durationMs) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_RESOURCES_LIST_FETCHED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .mcpMethod("resources/list")
                                .capabilityType("RESOURCE")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of(
                                                "resourceCount", resourceCount,
                                                "resources", resourcesList != null ? resourcesList : "[]")))
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: resources list fetch failed.
         */
        @Async("mcpAuditExecutor")
        public void auditClientResourcesListFailed(String sessionId,
                        String serverName,
                        String errorMessage,
                        long durationMs) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_RESOURCES_LIST_FETCHED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .mcpMethod("resources/list")
                                .capabilityType("RESOURCE")
                                .correlationId(generateCorrelationId())
                                .errorCode(McpErrorCode.INTERNAL_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: prompts list fetched from enterprise MCP server.
         */
        @Async("mcpAuditExecutor")
        public void auditClientPromptsListFetched(String sessionId,
                        String serverName,
                        int promptCount,
                        Object promptsList,
                        long durationMs) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_PROMPTS_LIST_FETCHED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .mcpMethod("prompts/list")
                                .capabilityType("PROMPT")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of(
                                                "promptCount", promptCount,
                                                "prompts", promptsList != null ? promptsList : "[]")))
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: prompts list fetch failed.
         */
        @Async("mcpAuditExecutor")
        public void auditClientPromptsListFailed(String sessionId,
                        String serverName,
                        String errorMessage,
                        long durationMs) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_PROMPTS_LIST_FETCHED)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .mcpMethod("prompts/list")
                                .capabilityType("PROMPT")
                                .correlationId(generateCorrelationId())
                                .errorCode(McpErrorCode.INTERNAL_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: tool invoked on an enterprise MCP server.
         */
        @Async("mcpAuditExecutor")
        public void auditClientToolInvocation(String sessionId,
                        String correlationId,
                        String serverName,
                        String toolName,
                        Object requestArgs,
                        Object responseContent,
                        long durationMs,
                        LocalDateTime firedAt,
                        Integer eventSequence) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_TOOL_INVOCATION)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(toolName)
                                .capabilityType("TOOL")
                                .mcpMethod("tools/call")
                                .correlationId(correlationId)
                                .requestPayload(toJson(Map.of(
                                                "toolName", toolName,
                                                "arguments", requestArgs != null ? requestArgs : Map.of())))
                                .responsePayload(toJson(Map.of(
                                                "content", responseContent != null ? responseContent : "[]")))
                                .durationMs(durationMs)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        /**
         * Audit: tool invocation failed on an enterprise MCP server.
         */
        @Async("mcpAuditExecutor")
        public void auditClientToolInvocationFailed(String sessionId,
                        String correlationId,
                        String serverName,
                        String toolName,
                        Object requestArgs,
                        String errorMessage,
                        McpErrorCode errorCode,
                        long durationMs,
                        LocalDateTime firedAt,
                        Integer eventSequence) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_TOOL_INVOCATION)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(toolName)
                                .capabilityType("TOOL")
                                .mcpMethod("tools/call")
                                .correlationId(correlationId)
                                .requestPayload(toJson(Map.of(
                                                "toolName", toolName,
                                                "arguments", requestArgs != null ? requestArgs : Map.of())))
                                .errorCode(errorCode != null ? errorCode.getCode()
                                                : McpErrorCode.INTERNAL_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        /**
         * Audit: resource read from enterprise MCP server.
         */
        @Async("mcpAuditExecutor")
        public void auditClientResourceRead(String sessionId,
                        String correlationId,
                        String serverName,
                        String resourceUri,
                        Object responseContent,
                        long durationMs,
                        LocalDateTime firedAt,
                        Integer eventSequence) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_RESOURCE_READ)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(resourceUri)
                                .capabilityType("RESOURCE")
                                .mcpMethod("resources/read")
                                .correlationId(correlationId)
                                .requestPayload(toJson(Map.of("uri", resourceUri)))
                                .responsePayload(toJson(Map.of(
                                                "content", responseContent != null ? responseContent : "[]")))
                                .durationMs(durationMs)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        /**
         * Audit: resource read failed on enterprise MCP server.
         */
        @Async("mcpAuditExecutor")
        public void auditClientResourceReadFailed(String sessionId,
                        String correlationId,
                        String serverName,
                        String resourceUri,
                        String errorMessage,
                        long durationMs,
                        LocalDateTime firedAt,
                        Integer eventSequence) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_RESOURCE_READ)
                                .module(AuditModule.WS_CLIENT)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(resourceUri)
                                .capabilityType("RESOURCE")
                                .mcpMethod("resources/read")
                                .correlationId(correlationId)
                                .requestPayload(toJson(Map.of("uri", resourceUri)))
                                .errorCode(McpErrorCode.INTERNAL_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 1 — AI Client <-> WS Server (Northbound)
        // ════════════════════════════════════════════════════════════════════

        @Async("mcpAuditExecutor")
        public void auditServerSessionInitialized(String sessionId,
                        String protocolVersion,
                        Object clientInfo,
                        Object clientCapabilities,
                        String requestId,
                        String agentName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_SESSION_INITIALIZED)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .mcpMethod("initialize")
                                .protocolVersion(protocolVersion)
                                .requestId(requestId)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "clientInfo", clientInfo != null ? clientInfo : Map.of(),
                                                "clientCapabilities",
                                                clientCapabilities != null ? clientCapabilities : Map.of())))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditServerToolsListRequested(String sessionId,
                        int toolCount,
                        long durationMs,
                        String requestId,
                        String agentName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_TOOLS_LIST_REQUESTED)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .mcpMethod("tools/list")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of("toolCount", toolCount)))
                                .durationMs(durationMs)
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditServerToolInvocation(String sessionId,
                        String toolName,
                        Object requestArgs,
                        Object responseContent,
                        long durationMs,
                        String agentName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_TOOL_INVOCATION)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .capabilityName(toolName)
                                .capabilityType("TOOL")
                                .mcpMethod("tools/call")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "toolName", toolName,
                                                "arguments", requestArgs != null ? requestArgs : Map.of())))
                                .responsePayload(toJson(Map.of(
                                                "content", responseContent != null ? responseContent : "[]")))
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: AI agent requested resources list from WS server.
         */
        @Async("mcpAuditExecutor")
        public void auditServerResourcesListRequested(String sessionId,
                        int resourceCount,
                        long durationMs,
                        String requestId,
                        String agentName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_RESOURCES_LIST_REQUESTED)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .mcpMethod("resources/list")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of("resourceCount", resourceCount)))
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: AI agent requested prompts list from WS server.
         */
        @Async("mcpAuditExecutor")
        public void auditServerPromptsListRequested(String sessionId,
                        int promptCount,
                        long durationMs,
                        String requestId,
                        String agentName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_PROMPTS_LIST_REQUESTED)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .mcpMethod("prompts/list")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of("promptCount", promptCount)))
                                .durationMs(durationMs)
                                .build());
        }

        /**
         * Audit: AI agent sent a notification to WS server.
         */
        @Async("mcpAuditExecutor")
        public void auditServerNotificationReceived(String sessionId,
                        String method,
                        Object params,
                        String agentName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_NOTIFICATION_RECEIVED)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.DEBUG)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .mcpMethod(method)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "method", method != null ? method : "unknown",
                                                "params", params != null ? params : Map.of())))
                                .build());
        }

        /**
         * Audit: AI agent session disconnected from WS server.
         */
        @Async("mcpAuditExecutor")
        public void auditServerSessionDisconnected(String sessionId, String agentName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_SESSION_DISCONNECTED)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        /**
         * Synchronous variant of {@link #auditServerSessionDisconnected} — used in
         * shutdown/cleanup paths where @Async would race with JVM termination.
         * <p>
         * NOT annotated with @Async so the DB write completes before the caller
         * returns.
         */
        public void auditServerSessionDisconnectedSync(String sessionId, String agentName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_SESSION_DISCONNECTED)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        /**
         * Audit: AI agent session reaped due to idle timeout.
         */
        @Async("mcpAuditExecutor")
        public void auditServerSessionIdleReaped(String sessionId, String agentName,
                        long idleDurationMs) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_SESSION_IDLE_REAPED)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .correlationId(generateCorrelationId())
                                .durationMs(idleDurationMs)
                                .errorMessage("Session reaped: idle timeout exceeded")
                                .build());
        }

        /**
         * Audit: AI agent request was rejected (e.g. stale session).
         */
        @Async("mcpAuditExecutor")
        public void auditServerRequestRejected(String sessionId,
                        String agentName,
                        JsonNode requestJson,
                        String errorMessage) {

                String requestId = "null";
                String method = "unknown";
                if (requestJson != null) {
                        if (requestJson.has("id")) {
                                requestId = requestJson.get("id").asText();
                        }
                        if (requestJson.has("method")) {
                                method = requestJson.get("method").asText();
                        }
                }

                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_REQUEST_REJECTED)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.DENIED)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .mcpMethod(method)
                                .correlationId(generateCorrelationId())
                                .errorCode(McpErrorCode.REQUEST_TIMEOUT.getCode())
                                .errorMessage(errorMessage)
                                .requestPayload(toJson(requestJson))
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 3 — Capability Registry CRUD
        // ════════════════════════════════════════════════════════════════════

        @Async("mcpAuditExecutor")
        public void auditRegistryCapabilityRegistered(String sessionId,
                        String serverName,
                        String publicName,
                        String capabilityType) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.REGISTRY_CAPABILITY_REGISTERED)
                                .module(AuditModule.CAPABILITY_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(publicName)
                                .capabilityType(capabilityType)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditRegistryCapabilityRemoved(String sessionId,
                        String serverName,
                        String publicName,
                        String capabilityType) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.REGISTRY_CAPABILITY_REMOVED)
                                .module(AuditModule.CAPABILITY_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(publicName)
                                .capabilityType(capabilityType)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditRegistryCapabilityUpdated(String sessionId,
                        String serverName,
                        String publicName,
                        String capabilityType) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.REGISTRY_CAPABILITY_UPDATED)
                                .module(AuditModule.CAPABILITY_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(publicName)
                                .capabilityType(capabilityType)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditRegistryBulkLoad(String sessionId,
                        String serverName,
                        int toolCount,
                        int resourceCount,
                        int promptCount,
                        long durationMs) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.REGISTRY_BULK_LOAD)
                                .module(AuditModule.CAPABILITY_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of(
                                                "toolCount", toolCount,
                                                "resourceCount", resourceCount,
                                                "promptCount", promptCount)))
                                .durationMs(durationMs)
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditRegistryServerRefresh(String sessionId,
                        String serverName,
                        AuditStatus status,
                        String errorMessage,
                        long durationMs) {
                McpAuditLog.McpAuditLogBuilder builder = McpAuditLog.builder()
                                .eventType(AuditEventType.REGISTRY_SERVER_REFRESH)
                                .module(AuditModule.CAPABILITY_REGISTRY)
                                .status(status)
                                .severity(status == AuditStatus.SUCCESS ? AuditSeverity.INFO : AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .durationMs(durationMs);

                if (errorMessage != null) {
                        builder.errorCode(McpErrorCode.REGISTRY_ERROR.getCode())
                                        .errorMessage(errorMessage);
                }

                persist(builder.build());
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 4 — Orchestration Layer
        // ════════════════════════════════════════════════════════════════════

        @Async("mcpAuditExecutor")
        public void auditOrchestrationToolExtracted(String correlationId,
                        String toolName,
                        String sessionId,
                        String requestId,
                        String agentName,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.ORCHESTRATION_TOOL_EXTRACTED)
                                .module(AuditModule.ORCHESTRATION_LAYER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.DEBUG)
                                .correlationId(correlationId)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .capabilityName(toolName)
                                .capabilityType("TOOL")
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditOrchestrationRegistryLookup(String correlationId,
                        String sessionId,
                        String publicCapabilityName,
                        String resolvedServerName,
                        long durationMs,
                        String requestId,
                        String agentName,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.ORCHESTRATION_REGISTRY_LOOKUP)
                                .module(AuditModule.ORCHESTRATION_LAYER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.DEBUG)
                                .correlationId(correlationId)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .capabilityName(publicCapabilityName)
                                .serverName(resolvedServerName)
                                .durationMs(durationMs)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditOrchestrationCallForwarded(String correlationId,
                        String sessionId,
                        String serverName,
                        String toolName,
                        long durationMs,
                        String requestId,
                        String agentName,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.ORCHESTRATION_RESPONSE_RETURNED)
                                .module(AuditModule.ORCHESTRATION_LAYER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(correlationId)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .serverName(serverName)
                                .capabilityName(toolName)
                                .capabilityType("TOOL")
                                .mcpMethod("tools/call")
                                .durationMs(durationMs)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditOrchestrationError(String correlationId,
                        String sessionId,
                        String serverName,
                        String capabilityName,
                        McpErrorCode errorCode,
                        String errorMessage,
                        String requestId,
                        String agentName,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.ORCHESTRATION_ERROR)
                                .module(AuditModule.ORCHESTRATION_LAYER)
                                .status(AuditStatus.ERROR)
                                .severity(AuditSeverity.ERROR)
                                .correlationId(correlationId)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .serverName(serverName)
                                .capabilityName(capabilityName)
                                .errorCode(errorCode.getCode())
                                .errorMessage(errorMessage)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 5 — PDP (Policy Decision Point)
        // Dual-write: pdp_audit_log (compliance retention) + mcp_audit_log (UI visibility).
        // Both linked by correlation_id.
        // ════════════════════════════════════════════════════════════════════

        @Async("mcpAuditExecutor")
        public void auditPdpEvaluationRequested(String correlationId,
                        String sessionId,
                        String subject,
                        String resource,
                        String action,
                        String serverName,
                        Object context,
                        String requestId,
                        String agentName,
                        LocalDateTime firedAt, Integer eventSequence) {
                persistPdp(PdpAuditLog.builder()
                                .eventType(AuditEventType.PDP_EVALUATION_REQUESTED)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(correlationId)
                                .pdpSubject(subject)
                                .pdpResource(resource)
                                .pdpAction(action)
                                .pdpContext(toJson(context))
                                .build());

                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_EVALUATION_REQUESTED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(correlationId)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .capabilityName(resource)
                                .serverName(serverName)
                                .mcpMethod(action)
                                .requestId(requestId)
                                .requestPayload(toJson(context))
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditPdpDecisionRendered(String correlationId,
                        String sessionId,
                        String subject,
                        String resource,
                        String action,
                        String decision,
                        String serverName,
                        Object context,
                        long durationMs,
                        String requestId,
                        String agentName,
                        LocalDateTime firedAt, Integer eventSequence) {
                AuditStatus decisionStatus = "ALLOW".equalsIgnoreCase(decision)
                                ? AuditStatus.SUCCESS : AuditStatus.DENIED;
                AuditSeverity decisionSeverity = "ALLOW".equalsIgnoreCase(decision)
                                ? AuditSeverity.INFO : AuditSeverity.WARN;

                persistPdp(PdpAuditLog.builder()
                                .eventType(AuditEventType.PDP_DECISION_RENDERED)
                                .status(decisionStatus)
                                .severity(decisionSeverity)
                                .correlationId(correlationId)
                                .pdpSubject(subject)
                                .pdpResource(resource)
                                .pdpAction(action)
                                .pdpDecision(decision)
                                .pdpContext(toJson(context))
                                .durationMs(durationMs)
                                .build());

                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_DECISION_RENDERED)
                                .module(AuditModule.PDP)
                                .status(decisionStatus)
                                .severity(decisionSeverity)
                                .correlationId(correlationId)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .capabilityName(resource)
                                .serverName(serverName)
                                .mcpMethod(action)
                                .capabilityType(decision)
                                .durationMs(durationMs)
                                .requestId(requestId)
                                .responsePayload(toJson(context))
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 5b — PDP Policy Management (admin CRUD → mcp_audit_log)
        // ════════════════════════════════════════════════════════════════════

        @Async("mcpAuditExecutor")
        public void auditPdpPolicyCreated(String policyName, String effect, String source,
                        String description, String policyText, String createdBy,
                        String tags, String originalPrompt,
                        Map<String, String> refs) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("policyName", policyName != null ? policyName : "");
                payload.put("effect", effect != null ? effect : "");
                payload.put("source", source != null ? source : "MANUAL");
                payload.put("description", description != null ? description : "");
                payload.put("policyText", policyText != null ? policyText : "");
                payload.put("createdBy", createdBy != null ? createdBy : "");
                payload.put("tags", tags != null ? tags : "");
                payload.put("originalPrompt", originalPrompt != null ? originalPrompt : "");
                if (refs != null) {
                        payload.put("referencedAgent", refs.getOrDefault("agentName", ""));
                        payload.put("referencedCapability", refs.getOrDefault("capabilityName", ""));
                        payload.put("referencedCapabilityType", refs.getOrDefault("capabilityType", ""));
                        payload.put("referencedServer", refs.getOrDefault("serverName", ""));
                        payload.put("referencedAction", refs.getOrDefault("actionName", ""));
                }

                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_POLICY_CREATED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .agentName(refs != null ? refs.get("agentName") : null)
                                .serverName(refs != null ? refs.get("serverName") : null)
                                .capabilityName(policyName)
                                .capabilityType(effect)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(payload))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditPdpPolicyUpdated(String policyName, String changedFields,
                        String description, String policyText, String effect,
                        String tags, Map<String, String> refs) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("policyName", policyName != null ? policyName : "");
                payload.put("changedFields", changedFields != null ? changedFields : "");
                payload.put("description", description != null ? description : "");
                payload.put("policyText", policyText != null ? policyText : "");
                payload.put("effect", effect != null ? effect : "");
                payload.put("tags", tags != null ? tags : "");
                if (refs != null) {
                        payload.put("referencedAgent", refs.getOrDefault("agentName", ""));
                        payload.put("referencedCapability", refs.getOrDefault("capabilityName", ""));
                        payload.put("referencedCapabilityType", refs.getOrDefault("capabilityType", ""));
                        payload.put("referencedServer", refs.getOrDefault("serverName", ""));
                        payload.put("referencedAction", refs.getOrDefault("actionName", ""));
                }

                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_POLICY_UPDATED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .agentName(refs != null ? refs.get("agentName") : null)
                                .serverName(refs != null ? refs.get("serverName") : null)
                                .capabilityName(policyName)
                                .capabilityType(effect)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(payload))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditPdpPolicyDeleted(String policyName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_POLICY_DELETED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .capabilityName(policyName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditPdpPolicyToggled(String policyName, boolean enabled) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_POLICY_TOGGLED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .capabilityName(policyName)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "policyName", policyName != null ? policyName : "",
                                                "enabled", enabled)))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditPdpPolicyValidated(String cedarText, boolean valid, String error) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_POLICY_VALIDATED)
                                .module(AuditModule.PDP)
                                .status(valid ? AuditStatus.SUCCESS : AuditStatus.FAILURE)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of("policyText",
                                                cedarText != null && cedarText.length() > 500
                                                                ? cedarText.substring(0, 500) + "...[truncated]"
                                                                : (cedarText != null ? cedarText : ""))))
                                .errorMessage(error)
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditPdpEngineReloaded(int policyCount) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_ENGINE_RELOADED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of("policyCount", policyCount)))
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 5c — PDP LLM Chat (admin policy generation → mcp_audit_log)
        // ════════════════════════════════════════════════════════════════════

        @Async("mcpAuditExecutor")
        public void auditPdpLlmChatRequested(String prompt, int messageCount) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_LLM_CHAT_REQUESTED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "prompt", prompt != null && prompt.length() > 500
                                                                ? prompt.substring(0, 500) + "...[truncated]"
                                                                : (prompt != null ? prompt : ""),
                                                "messageCount", messageCount)))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditPdpLlmChatCompleted(String prompt, String responseText,
                        long durationMs, boolean success) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_LLM_CHAT_COMPLETED)
                                .module(AuditModule.PDP)
                                .status(success ? AuditStatus.SUCCESS : AuditStatus.FAILURE)
                                .severity(success ? AuditSeverity.INFO : AuditSeverity.WARN)
                                .correlationId(generateCorrelationId())
                                .durationMs(durationMs)
                                .requestPayload(toJson(Map.of("prompt",
                                                prompt != null && prompt.length() > 500
                                                                ? prompt.substring(0, 500) + "...[truncated]"
                                                                : (prompt != null ? prompt : ""))))
                                .responsePayload(toJson(Map.of("response",
                                                responseText != null && responseText.length() > 500
                                                                ? responseText.substring(0, 500) + "...[truncated]"
                                                                : (responseText != null ? responseText : ""))))
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 5d — PDP Custom Attributes (admin CRUD → mcp_audit_log)
        // ════════════════════════════════════════════════════════════════════

        @Async("mcpAuditExecutor")
        public void auditPdpCustomAttrCreated(String attrName, String valueSource, String dataType) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_CUSTOM_ATTR_CREATED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .capabilityName(attrName)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "attributeName", attrName != null ? attrName : "",
                                                "valueSource", valueSource != null ? valueSource : "",
                                                "dataType", dataType != null ? dataType : "")))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditPdpCustomAttrUpdated(String attrName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_CUSTOM_ATTR_UPDATED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .capabilityName(attrName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditPdpCustomAttrDeleted(String attrName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_CUSTOM_ATTR_DELETED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .capabilityName(attrName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditPdpCustomAttrToggled(String attrName, boolean enabled) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.PDP_CUSTOM_ATTR_TOGGLED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .capabilityName(attrName)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "attributeName", attrName != null ? attrName : "",
                                                "enabled", enabled)))
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 6 — Server Configuration Management
        // ════════════════════════════════════════════════════════════════════

        @Async("mcpAuditExecutor")
        public void auditServerConfigCreated(String serverName, String url) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_CONFIG_CREATED)
                                .module(AuditModule.SERVER_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "serverName", serverName,
                                                "url", url != null ? url : "")))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditServerConfigUpdated(String serverName, String url) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_CONFIG_UPDATED)
                                .module(AuditModule.SERVER_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "serverName", serverName,
                                                "url", url != null ? url : "")))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditServerConfigDeleted(String serverName) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.SERVER_CONFIG_DELETED)
                                .module(AuditModule.SERVER_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 7 — Agent Registry & Approval Workflow
        // ════════════════════════════════════════════════════════════════════

        public void auditAgentApproved(UUID agentId,
                        String agentName,
                        String agentVersion,
                        String previousApprovalStatus,
                        String adminActor,
                        String adminIp) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.AGENT_APPROVED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .agentName(agentName)
                                .mcpMethod("admin/agents/approve")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "agentId", agentId != null ? agentId.toString() : "",
                                                "agentName", agentName != null ? agentName : "unknown",
                                                "agentVersion", agentVersion != null ? agentVersion : "",
                                                "previousApprovalStatus",
                                                previousApprovalStatus != null ? previousApprovalStatus : "UNKNOWN",
                                                "newApprovalStatus", "APPROVED",
                                                "adminActor", adminActor != null ? adminActor : "unknown",
                                                "adminIp", adminIp != null ? adminIp : "unknown")))
                                .build());
        }

        public void auditAgentBlocked(UUID agentId,
                        String agentName,
                        String agentVersion,
                        String previousApprovalStatus,
                        String adminActor,
                        String adminIp) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.AGENT_BLOCKED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .agentName(agentName)
                                .mcpMethod("admin/agents/block")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "agentId", agentId != null ? agentId.toString() : "",
                                                "agentName", agentName != null ? agentName : "unknown",
                                                "agentVersion", agentVersion != null ? agentVersion : "",
                                                "previousApprovalStatus",
                                                previousApprovalStatus != null ? previousApprovalStatus : "UNKNOWN",
                                                "newApprovalStatus", "BLOCKED",
                                                "adminActor", adminActor != null ? adminActor : "unknown",
                                                "adminIp", adminIp != null ? adminIp : "unknown")))
                                .build());
        }

        public void auditAgentConnectionRejected(String sessionId,
                        String requestId,
                        String agentName,
                        String agentVersion,
                        String method,
                        String transport,
                        String reason) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.AGENT_CONNECTION_REJECTED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.DENIED)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .requestId(requestId)
                                .agentName(agentName)
                                .mcpMethod(method != null ? method : "initialize")
                                .correlationId(generateCorrelationId())
                                .errorCode(McpErrorCode.AGENT_BLOCKED.getCode())
                                .errorMessage(reason != null ? reason : "Agent rejected by admin policy")
                                .requestPayload(toJson(Map.of(
                                                "agentName", agentName != null ? agentName : "unknown",
                                                "agentVersion", agentVersion != null ? agentVersion : "",
                                                "transport", transport != null ? transport : "HTTP")))
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // AREA 8 — Capability Access Profiles
        // ════════════════════════════════════════════════════════════════════

        @Async("mcpAuditExecutor")
        public void auditCapabilityProfileCreated(String profileName, UUID profileId,
                        String description, int ruleCount) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CAPABILITY_PROFILE_CREATED)
                                .module(AuditModule.CAPABILITY_PROFILES)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .capabilityName(profileName)
                                .requestPayload(toJson(Map.of(
                                                "profileName", profileName != null ? profileName : "",
                                                "profileId", profileId != null ? profileId.toString() : "",
                                                "description", description != null ? description : "",
                                                "ruleCount", ruleCount)))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditCapabilityProfileUpdated(String profileName, UUID profileId,
                        String changedFields) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CAPABILITY_PROFILE_UPDATED)
                                .module(AuditModule.CAPABILITY_PROFILES)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .capabilityName(profileName)
                                .requestPayload(toJson(Map.of(
                                                "profileName", profileName != null ? profileName : "",
                                                "profileId", profileId != null ? profileId.toString() : "",
                                                "changedFields", changedFields != null ? changedFields : "")))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditCapabilityProfileDeleted(String profileName, UUID profileId) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CAPABILITY_PROFILE_DELETED)
                                .module(AuditModule.CAPABILITY_PROFILES)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .correlationId(generateCorrelationId())
                                .capabilityName(profileName)
                                .requestPayload(toJson(Map.of(
                                                "profileName", profileName != null ? profileName : "",
                                                "profileId", profileId != null ? profileId.toString() : "")))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditCapabilityProfileAssigned(String profileName, UUID profileId,
                        String agentName, UUID agentId) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CAPABILITY_PROFILE_ASSIGNED)
                                .module(AuditModule.CAPABILITY_PROFILES)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .capabilityName(profileName)
                                .agentName(agentName)
                                .requestPayload(toJson(Map.of(
                                                "profileName", profileName != null ? profileName : "",
                                                "profileId", profileId != null ? profileId.toString() : "",
                                                "agentName", agentName != null ? agentName : "",
                                                "agentId", agentId != null ? agentId.toString() : "")))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditCapabilityProfileUnassigned(String profileName, UUID profileId,
                        String agentName, UUID agentId) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CAPABILITY_PROFILE_UNASSIGNED)
                                .module(AuditModule.CAPABILITY_PROFILES)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .capabilityName(profileName)
                                .agentName(agentName)
                                .requestPayload(toJson(Map.of(
                                                "profileName", profileName != null ? profileName : "",
                                                "profileId", profileId != null ? profileId.toString() : "",
                                                "agentName", agentName != null ? agentName : "",
                                                "agentId", agentId != null ? agentId.toString() : "")))
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditCapabilityAccessDenied(String sessionId, String correlationId,
                        String agentName, String capabilityName, String capabilityType,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CAPABILITY_ACCESS_DENIED)
                                .module(AuditModule.CAPABILITY_PROFILES)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .correlationId(correlationId != null ? correlationId : generateCorrelationId())
                                .agentName(agentName)
                                .capabilityName(capabilityName)
                                .capabilityType(capabilityType)
                                .errorMessage("Capability access denied — no profile grants access")
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        @Async("mcpAuditExecutor")
        public void auditCapabilityAccessGranted(String sessionId, String correlationId,
                        String agentName, String capabilityName, String capabilityType,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.CAPABILITY_ACCESS_GRANTED)
                                .module(AuditModule.CAPABILITY_PROFILES)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .correlationId(correlationId != null ? correlationId : generateCorrelationId())
                                .agentName(agentName)
                                .capabilityName(capabilityName)
                                .capabilityType(capabilityType)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // GENERAL — System events
        // ════════════════════════════════════════════════════════════════════

        @Async("mcpAuditExecutor")
        public void auditSystemEvent(AuditEventType eventType,
                        AuditStatus status,
                        AuditSeverity severity,
                        String message) {
                persist(McpAuditLog.builder()
                                .eventType(eventType)
                                .module(AuditModule.SYSTEM)
                                .status(status)
                                .severity(severity)
                                .correlationId(generateCorrelationId())
                                .errorMessage(message)
                                .build());
        }

        /**
         * Generic error audit — usable from any module when an unexpected
         * {@link McpAuditException} is caught.
         */
        @Async("mcpAuditExecutor")
        public void auditError(AuditModule module,
                        AuditEventType eventType,
                        String serverName,
                        McpAuditException ex) {
                persist(McpAuditLog.builder()
                                .eventType(eventType)
                                .module(module)
                                .status(AuditStatus.ERROR)
                                .severity(AuditSeverity.ERROR)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .errorCode(ex.getErrorCode().getCode())
                                .errorMessage(ex.getMessage())
                                .errorData(toJson(ex.getErrorData()))
                                .build());
        }

        // ════════════════════════════════════════════════════════════════════
        // INTERNAL HELPERS
        // ════════════════════════════════════════════════════════════════════

        // ════════════════════════════════════════════════════════════════════
        // AREA 0 — Authentication Events
        // ════════════════════════════════════════════════════════════════════

        /**
         * Audit: OAuth2 JWT authentication succeeded.
         * Logs the full JWT claims payload for debugging and data discovery.
         */
        @Async("mcpAuditExecutor")
        public void auditOAuth2AuthSuccess(String sessionId, String agentClientId, String subject,
                        List<String> roles, String tokenType, String userIdentity,
                        Map<String, Object> rawClaims, String requestId) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.OAUTH2_AUTH_SUCCESS)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .requestId(requestId)
                                .authMethod("OAUTH2")
                                .authIdentity(subject)
                                .agentClientId(agentClientId)
                                .agentRoles(roles)
                                .tokenType(tokenType)
                                .userIdentity(userIdentity)
                                .agentName(agentClientId)
                                .requestPayload(toJson(rawClaims))
                                .build());
        }

        /**
         * Audit: OAuth2 JWT authentication failed.
         * Called when Spring Security rejects a token and we can intercept it,
         * or when the SecurityContext is unexpectedly empty.
         */
        @Async("mcpAuditExecutor")
        public void auditOAuth2AuthFailure(String remoteAddr, String errorMessage, String requestId) {
                persist(McpAuditLog.builder()
                                .eventType(AuditEventType.OAUTH2_AUTH_FAILURE)
                                .module(AuditModule.WS_SERVER)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.WARN)
                                .requestId(requestId)
                                .authMethod("OAUTH2")
                                .errorMessage(errorMessage)
                                .agentName(remoteAddr)
                                .build());
        }

        private void persist(McpAuditLog auditLog) {
                try {
                        // Fire-time fallback: set timestamp if not already captured by caller
                        if (auditLog.getTimestamp() == null) {
                                auditLog.setTimestamp(LocalDateTime.now());
                        }
                        repository.save(auditLog);
                        log.debug("Audit [{}] {} — {} | server={} capability={}",
                                        auditLog.getModule(),
                                        auditLog.getEventType(),
                                        auditLog.getStatus(),
                                        auditLog.getServerName(),
                                        auditLog.getCapabilityName());
                } catch (Exception e) {
                        // Audit persistence must NEVER propagate exceptions to callers
                        log.error("Failed to persist audit log [{}] {}: {}",
                                        auditLog.getModule(), auditLog.getEventType(), e.getMessage(), e);
                }
        }

        private void persistPdp(PdpAuditLog pdpLog) {
                try {
                        pdpRepository.save(pdpLog);
                        log.debug("PDP Audit [{}] {} — {} | subject={} resource={} decision={}",
                                        pdpLog.getEventType(),
                                        pdpLog.getStatus(),
                                        pdpLog.getCorrelationId(),
                                        pdpLog.getPdpSubject(),
                                        pdpLog.getPdpResource(),
                                        pdpLog.getPdpDecision());
                } catch (Exception e) {
                        // Audit persistence must NEVER propagate exceptions to callers
                        log.error("Failed to persist PDP audit log [{}] {}: {}",
                                        pdpLog.getEventType(), pdpLog.getCorrelationId(), e.getMessage(), e);
                }
        }

        private String generateCorrelationId() {
                return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        private JsonNode toJson(Object value) {
                if (value == null) {
                        return null;
                }
                try {
                        return objectMapper.valueToTree(value);
                } catch (Exception e) {
                        log.warn("Failed to serialize audit payload: {}", e.getMessage());
                        return null;
                }
        }
}
