package com.ws.wsAgenticSecurity.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurity.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurity.audit.constants.AuditModule;
import com.ws.wsAgenticSecurity.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurity.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurity.audit.entity.McpAuditLog;
import com.ws.wsAgenticSecurity.audit.entity.PdpAuditLog;
import com.ws.wsAgenticSecurity.audit.error.McpAuditException;
import com.ws.wsAgenticSecurity.audit.error.McpErrorCode;
import com.ws.wsAgenticSecurity.audit.repository.McpAuditLogRepository;
import com.ws.wsAgenticSecurity.audit.repository.PdpAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Asynchronous audit-logging service for the WS MCP Gateway.
 *
 * <p>Every public method is {@code @Async} and runs on the dedicated
 * {@code mcpAuditExecutor} thread pool so that audit persistence
 * <strong>never</strong> blocks the request hot path.
 *
 * <h3>Covered Activity Areas</h3>
 * <ol>
 *   <li>AI Client  &lt;-&gt;  WS Server  (Northbound)  → {@code mcp_audit_log}</li>
 *   <li>WS Client  &lt;-&gt;  Enterprise MCP Server  (Southbound)  → {@code mcp_audit_log}</li>
 *   <li>Capability Registry CRUD  → {@code mcp_audit_log}</li>
 *   <li>Orchestration Layer  → {@code mcp_audit_log}</li>
 *   <li>PDP (Policy Decision Point)  → {@code pdp_audit_log} (separate table)</li>
 * </ol>
 *
 * <p>Areas 1–4 persist to {@code mcp_audit_log}. Area 5 persists to
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
        this.objectMapper = new ObjectMapper();
    }

    // ════════════════════════════════════════════════════════════════════
    //  AREA 2 — WS Client <-> Enterprise MCP Server (Southbound)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Audit: client successfully initialized a session with an enterprise MCP server.
     */
    @Async("mcpAuditExecutor")
    public void auditClientSessionInitialized(String serverName,
                                              String protocolVersion,
                                              Map<String, Object> serverInfo,
                                              Map<String, Object> capabilities,
                                              long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_SESSION_INITIALIZED)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .mcpMethod("initialize")
                .protocolVersion(protocolVersion)
                .correlationId(generateCorrelationId())
                .requestPayload(toJson(Map.of("serverName", serverName)))
                .responsePayload(toJson(Map.of(
                        "serverInfo", serverInfo != null ? serverInfo : Map.of(),
                        "capabilities", capabilities != null ? capabilities : Map.of()
                )))
                .durationMs(durationMs)
                .build());
    }

    /**
     * Audit: client session initialization failed.
     */
    @Async("mcpAuditExecutor")
    public void auditClientSessionInitFailed(String serverName,
                                             String errorMessage,
                                             long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_SESSION_INITIALIZED)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.FAILURE)
                .severity(AuditSeverity.ERROR)
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
    public void auditClientSessionDisconnected(String serverName) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_SESSION_DISCONNECTED)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .correlationId(generateCorrelationId())
                .build());
    }

    /**
     * Audit: tools list fetched from enterprise MCP server.
     */
    @Async("mcpAuditExecutor")
    public void auditClientToolsListFetched(String serverName,
                                            int toolCount,
                                            Object toolsList,
                                            long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_TOOLS_LIST_FETCHED)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .mcpMethod("tools/list")
                .capabilityType("TOOL")
                .correlationId(generateCorrelationId())
                .responsePayload(toJson(Map.of(
                        "toolCount", toolCount,
                        "tools", toolsList != null ? toolsList : "[]"
                )))
                .durationMs(durationMs)
                .build());
    }

    /**
     * Audit: tools list fetch failed.
     */
    @Async("mcpAuditExecutor")
    public void auditClientToolsListFailed(String serverName,
                                           String errorMessage,
                                           long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_TOOLS_LIST_FETCHED)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.FAILURE)
                .severity(AuditSeverity.ERROR)
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
    public void auditClientResourcesListFetched(String serverName,
                                                int resourceCount,
                                                Object resourcesList,
                                                long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_RESOURCES_LIST_FETCHED)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .mcpMethod("resources/list")
                .capabilityType("RESOURCE")
                .correlationId(generateCorrelationId())
                .responsePayload(toJson(Map.of(
                        "resourceCount", resourceCount,
                        "resources", resourcesList != null ? resourcesList : "[]"
                )))
                .durationMs(durationMs)
                .build());
    }

    /**
     * Audit: resources list fetch failed.
     */
    @Async("mcpAuditExecutor")
    public void auditClientResourcesListFailed(String serverName,
                                               String errorMessage,
                                               long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_RESOURCES_LIST_FETCHED)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.FAILURE)
                .severity(AuditSeverity.ERROR)
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
    public void auditClientPromptsListFetched(String serverName,
                                              int promptCount,
                                              Object promptsList,
                                              long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_PROMPTS_LIST_FETCHED)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .mcpMethod("prompts/list")
                .capabilityType("PROMPT")
                .correlationId(generateCorrelationId())
                .responsePayload(toJson(Map.of(
                        "promptCount", promptCount,
                        "prompts", promptsList != null ? promptsList : "[]"
                )))
                .durationMs(durationMs)
                .build());
    }

    /**
     * Audit: prompts list fetch failed.
     */
    @Async("mcpAuditExecutor")
    public void auditClientPromptsListFailed(String serverName,
                                             String errorMessage,
                                             long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_PROMPTS_LIST_FETCHED)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.FAILURE)
                .severity(AuditSeverity.ERROR)
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
    public void auditClientToolInvocation(String serverName,
                                          String toolName,
                                          Object requestArgs,
                                          Object responseContent,
                                          long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_TOOL_INVOCATION)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .capabilityName(toolName)
                .capabilityType("TOOL")
                .mcpMethod("tools/call")
                .correlationId(generateCorrelationId())
                .requestPayload(toJson(Map.of(
                        "toolName", toolName,
                        "arguments", requestArgs != null ? requestArgs : Map.of()
                )))
                .responsePayload(toJson(Map.of(
                        "content", responseContent != null ? responseContent : "[]"
                )))
                .durationMs(durationMs)
                .build());
    }

    /**
     * Audit: tool invocation failed on an enterprise MCP server.
     */
    @Async("mcpAuditExecutor")
    public void auditClientToolInvocationFailed(String serverName,
                                                String toolName,
                                                Object requestArgs,
                                                String errorMessage,
                                                McpErrorCode errorCode,
                                                long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_TOOL_INVOCATION)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.FAILURE)
                .severity(AuditSeverity.ERROR)
                .serverName(serverName)
                .capabilityName(toolName)
                .capabilityType("TOOL")
                .mcpMethod("tools/call")
                .correlationId(generateCorrelationId())
                .requestPayload(toJson(Map.of(
                        "toolName", toolName,
                        "arguments", requestArgs != null ? requestArgs : Map.of()
                )))
                .errorCode(errorCode != null ? errorCode.getCode() : McpErrorCode.INTERNAL_ERROR.getCode())
                .errorMessage(errorMessage)
                .durationMs(durationMs)
                .build());
    }

    /**
     * Audit: resource read from enterprise MCP server.
     */
    @Async("mcpAuditExecutor")
    public void auditClientResourceRead(String serverName,
                                        String resourceUri,
                                        Object responseContent,
                                        long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_RESOURCE_READ)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .capabilityName(resourceUri)
                .capabilityType("RESOURCE")
                .mcpMethod("resources/read")
                .correlationId(generateCorrelationId())
                .requestPayload(toJson(Map.of("uri", resourceUri)))
                .responsePayload(toJson(Map.of(
                        "content", responseContent != null ? responseContent : "[]"
                )))
                .durationMs(durationMs)
                .build());
    }

    /**
     * Audit: resource read failed on enterprise MCP server.
     */
    @Async("mcpAuditExecutor")
    public void auditClientResourceReadFailed(String serverName,
                                              String resourceUri,
                                              String errorMessage,
                                              long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.CLIENT_RESOURCE_READ)
                .module(AuditModule.WS_CLIENT)
                .status(AuditStatus.FAILURE)
                .severity(AuditSeverity.ERROR)
                .serverName(serverName)
                .capabilityName(resourceUri)
                .capabilityType("RESOURCE")
                .mcpMethod("resources/read")
                .correlationId(generateCorrelationId())
                .requestPayload(toJson(Map.of("uri", resourceUri)))
                .errorCode(McpErrorCode.INTERNAL_ERROR.getCode())
                .errorMessage(errorMessage)
                .durationMs(durationMs)
                .build());
    }

    // ════════════════════════════════════════════════════════════════════
    //  AREA 1 — AI Client <-> WS Server (Northbound)
    //  (Stub methods — to be wired when Orchestration Layer is built)
    // ════════════════════════════════════════════════════════════════════

    @Async("mcpAuditExecutor")
    public void auditServerSessionInitialized(String sessionId,
                                              String protocolVersion,
                                              Object clientInfo,
                                              Object clientCapabilities) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.SERVER_SESSION_INITIALIZED)
                .module(AuditModule.WS_SERVER)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .sessionId(sessionId)
                .mcpMethod("initialize")
                .protocolVersion(protocolVersion)
                .correlationId(generateCorrelationId())
                .requestPayload(toJson(Map.of(
                        "clientInfo", clientInfo != null ? clientInfo : Map.of(),
                        "clientCapabilities", clientCapabilities != null ? clientCapabilities : Map.of()
                )))
                .build());
    }

    @Async("mcpAuditExecutor")
    public void auditServerToolsListRequested(String sessionId,
                                               int toolCount,
                                               long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.SERVER_TOOLS_LIST_REQUESTED)
                .module(AuditModule.WS_SERVER)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .sessionId(sessionId)
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
                                          long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.SERVER_TOOL_INVOCATION)
                .module(AuditModule.WS_SERVER)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .sessionId(sessionId)
                .capabilityName(toolName)
                .capabilityType("TOOL")
                .mcpMethod("tools/call")
                .correlationId(generateCorrelationId())
                .requestPayload(toJson(Map.of(
                        "toolName", toolName,
                        "arguments", requestArgs != null ? requestArgs : Map.of()
                )))
                .responsePayload(toJson(Map.of(
                        "content", responseContent != null ? responseContent : "[]"
                )))
                .durationMs(durationMs)
                .build());
    }

    /**
     * Audit: AI agent requested resources list from WS server.
     */
    @Async("mcpAuditExecutor")
    public void auditServerResourcesListRequested(String sessionId,
                                                   int resourceCount,
                                                   long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.SERVER_RESOURCES_LIST_REQUESTED)
                .module(AuditModule.WS_SERVER)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .sessionId(sessionId)
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
                                                 long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.SERVER_PROMPTS_LIST_REQUESTED)
                .module(AuditModule.WS_SERVER)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .sessionId(sessionId)
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
                                                 Object params) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.SERVER_NOTIFICATION_RECEIVED)
                .module(AuditModule.WS_SERVER)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.DEBUG)
                .sessionId(sessionId)
                .mcpMethod(method)
                .correlationId(generateCorrelationId())
                .requestPayload(toJson(Map.of(
                        "method", method != null ? method : "unknown",
                        "params", params != null ? params : Map.of()
                )))
                .build());
    }

    /**
     * Audit: AI agent session disconnected from WS server.
     */
    @Async("mcpAuditExecutor")
    public void auditServerSessionDisconnected(String sessionId) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.SERVER_SESSION_DISCONNECTED)
                .module(AuditModule.WS_SERVER)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .sessionId(sessionId)
                .correlationId(generateCorrelationId())
                .build());
    }

    // ════════════════════════════════════════════════════════════════════
    //  AREA 3 — Capability Registry CRUD
    //  (Stub methods — to be wired when Registry Service is built)
    // ════════════════════════════════════════════════════════════════════

    @Async("mcpAuditExecutor")
    public void auditRegistryCapabilityRegistered(String serverName,
                                                  String publicName,
                                                  String capabilityType) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.REGISTRY_CAPABILITY_REGISTERED)
                .module(AuditModule.CAPABILITY_REGISTRY)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .capabilityName(publicName)
                .capabilityType(capabilityType)
                .correlationId(generateCorrelationId())
                .build());
    }

    @Async("mcpAuditExecutor")
    public void auditRegistryCapabilityRemoved(String serverName,
                                               String publicName,
                                               String capabilityType) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.REGISTRY_CAPABILITY_REMOVED)
                .module(AuditModule.CAPABILITY_REGISTRY)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .capabilityName(publicName)
                .capabilityType(capabilityType)
                .correlationId(generateCorrelationId())
                .build());
    }

    @Async("mcpAuditExecutor")
    public void auditRegistryCapabilityUpdated(String serverName,
                                               String publicName,
                                               String capabilityType) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.REGISTRY_CAPABILITY_UPDATED)
                .module(AuditModule.CAPABILITY_REGISTRY)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .capabilityName(publicName)
                .capabilityType(capabilityType)
                .correlationId(generateCorrelationId())
                .build());
    }

    @Async("mcpAuditExecutor")
    public void auditRegistryBulkLoad(String serverName,
                                      int toolCount,
                                      int resourceCount,
                                      int promptCount,
                                      long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.REGISTRY_BULK_LOAD)
                .module(AuditModule.CAPABILITY_REGISTRY)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .serverName(serverName)
                .correlationId(generateCorrelationId())
                .responsePayload(toJson(Map.of(
                        "toolCount", toolCount,
                        "resourceCount", resourceCount,
                        "promptCount", promptCount
                )))
                .durationMs(durationMs)
                .build());
    }

    @Async("mcpAuditExecutor")
    public void auditRegistryServerRefresh(String serverName,
                                           AuditStatus status,
                                           String errorMessage,
                                           long durationMs) {
        McpAuditLog.McpAuditLogBuilder builder = McpAuditLog.builder()
                .eventType(AuditEventType.REGISTRY_SERVER_REFRESH)
                .module(AuditModule.CAPABILITY_REGISTRY)
                .status(status)
                .severity(status == AuditStatus.SUCCESS ? AuditSeverity.INFO : AuditSeverity.ERROR)
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
    //  AREA 4 — Orchestration Layer
    //  (Stub methods — to be wired when Orchestration Layer is built)
    // ════════════════════════════════════════════════════════════════════

    @Async("mcpAuditExecutor")
    public void auditOrchestrationToolExtracted(String correlationId,
                                                String toolName,
                                                String sessionId) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.ORCHESTRATION_TOOL_EXTRACTED)
                .module(AuditModule.ORCHESTRATION_LAYER)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.DEBUG)
                .correlationId(correlationId)
                .sessionId(sessionId)
                .capabilityName(toolName)
                .capabilityType("TOOL")
                .build());
    }

    @Async("mcpAuditExecutor")
    public void auditOrchestrationRegistryLookup(String correlationId,
                                                 String publicCapabilityName,
                                                 String resolvedServerName,
                                                 long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.ORCHESTRATION_REGISTRY_LOOKUP)
                .module(AuditModule.ORCHESTRATION_LAYER)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.DEBUG)
                .correlationId(correlationId)
                .capabilityName(publicCapabilityName)
                .serverName(resolvedServerName)
                .durationMs(durationMs)
                .build());
    }

    @Async("mcpAuditExecutor")
    public void auditOrchestrationCallForwarded(String correlationId,
                                                String serverName,
                                                String toolName,
                                                long durationMs) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.ORCHESTRATION_CALL_FORWARDED)
                .module(AuditModule.ORCHESTRATION_LAYER)
                .status(AuditStatus.SUCCESS)
                .severity(AuditSeverity.INFO)
                .correlationId(correlationId)
                .serverName(serverName)
                .capabilityName(toolName)
                .capabilityType("TOOL")
                .mcpMethod("tools/call")
                .durationMs(durationMs)
                .build());
    }

    @Async("mcpAuditExecutor")
    public void auditOrchestrationError(String correlationId,
                                        String serverName,
                                        String capabilityName,
                                        McpErrorCode errorCode,
                                        String errorMessage) {
        persist(McpAuditLog.builder()
                .eventType(AuditEventType.ORCHESTRATION_ERROR)
                .module(AuditModule.ORCHESTRATION_LAYER)
                .status(AuditStatus.ERROR)
                .severity(AuditSeverity.ERROR)
                .correlationId(correlationId)
                .serverName(serverName)
                .capabilityName(capabilityName)
                .errorCode(errorCode.getCode())
                .errorMessage(errorMessage)
                .build());
    }

    // ════════════════════════════════════════════════════════════════════
    //  AREA 5 — PDP (Policy Decision Point)
    //  Persists to dedicated `pdp_audit_log` table.
    //  Linked to mcp_audit_log via correlation_id (logical join, not FK).
    // ════════════════════════════════════════════════════════════════════

    @Async("mcpAuditExecutor")
    public void auditPdpEvaluationRequested(String correlationId,
                                            String subject,
                                            String resource,
                                            String action,
                                            Object context) {
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
    }

    @Async("mcpAuditExecutor")
    public void auditPdpDecisionRendered(String correlationId,
                                         String subject,
                                         String resource,
                                         String action,
                                         String decision,
                                         Object context,
                                         long durationMs) {
        persistPdp(PdpAuditLog.builder()
                .eventType(AuditEventType.PDP_DECISION_RENDERED)
                .status("ALLOW".equalsIgnoreCase(decision) ? AuditStatus.SUCCESS : AuditStatus.DENIED)
                .severity("ALLOW".equalsIgnoreCase(decision) ? AuditSeverity.INFO : AuditSeverity.WARN)
                .correlationId(correlationId)
                .pdpSubject(subject)
                .pdpResource(resource)
                .pdpAction(action)
                .pdpDecision(decision)
                .pdpContext(toJson(context))
                .durationMs(durationMs)
                .build());
    }

    // ════════════════════════════════════════════════════════════════════
    //  GENERAL — System events
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
    //  INTERNAL HELPERS
    // ════════════════════════════════════════════════════════════════════

    private void persist(McpAuditLog auditLog) {
        try {
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
