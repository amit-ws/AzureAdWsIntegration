package com.ws.wsAgenticSecurityGateway.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.GatewayAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.entity.PdpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.error.GatewayErrorCode;
import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.sts.model.MintedToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class GatewayAuditService {

        private final GatewayAuditLogRepository repository;
        private final PdpAuditLogRepository pdpRepository;
        private final ObjectMapper objectMapper;

        private final java.util.concurrent.ConcurrentHashMap<String, AuditIdentityContext> sessionIdentityCache =
                new java.util.concurrent.ConcurrentHashMap<>();

        public record AuditIdentityContext(
                String tokenType,
                String userIdentity,
                String humanUserId,
                String authMethod,
                String authIdentity,
                String agentClientId,
                List<String> agentRoles,
                String nhiId,
                String sourceIp,
                String wsTenantName
        ) {
                public AuditIdentityContext(
                        String tokenType, String userIdentity, String humanUserId,
                        String authMethod, String authIdentity, String agentClientId,
                        List<String> agentRoles) {
                    this(tokenType, userIdentity, humanUserId, authMethod, authIdentity,
                         agentClientId, agentRoles, null, null, null);
                }

                public AuditIdentityContext(
                        String tokenType, String userIdentity, String humanUserId,
                        String authMethod, String authIdentity, String agentClientId,
                        List<String> agentRoles, String nhiId) {
                    this(tokenType, userIdentity, humanUserId, authMethod, authIdentity,
                         agentClientId, agentRoles, nhiId, null, null);
                }

                public AuditIdentityContext(
                        String tokenType, String userIdentity, String humanUserId,
                        String authMethod, String authIdentity, String agentClientId,
                        List<String> agentRoles, String nhiId, String sourceIp) {
                    this(tokenType, userIdentity, humanUserId, authMethod, authIdentity,
                         agentClientId, agentRoles, nhiId, sourceIp, null);
                }
        }

        public void registerSessionIdentity(String sessionId, AuditIdentityContext ctx) {
                if (sessionId != null && ctx != null) {
                        sessionIdentityCache.put(sessionId, ctx);
                        log.debug("Audit identity registered for session {}: tokenType={}, user={}",
                                sessionId, ctx.tokenType(), ctx.userIdentity());
                }
        }

        public void evictSessionIdentity(String sessionId) {
                if (sessionId != null) {
                        sessionIdentityCache.remove(sessionId);
                }
        }

        public GatewayAuditService(GatewayAuditLogRepository repository,
                        PdpAuditLogRepository pdpRepository) {
                this.repository = repository;
                this.pdpRepository = pdpRepository;
                this.objectMapper = new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        @Async("auditExecutor")
        public void auditClientSessionInitialized(String sessionId,
                        String serverName,
                        String protocolVersion,
                        Map<String, Object> serverInfo,
                        Map<String, Object> capabilities,
                        long durationMs) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_SESSION_INITIALIZED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .protocolMethod("initialize")
                                .protocolVersion(protocolVersion)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of("serverName", serverName)))
                                .responsePayload(toJson(Map.of(
                                                "serverInfo", serverInfo != null ? serverInfo : Map.of(),
                                                "capabilities", capabilities != null ? capabilities : Map.of())))
                                .durationMs(durationMs)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientSessionInitFailed(String sessionId,
                        String serverName,
                        String errorMessage,
                        long durationMs) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_SESSION_INITIALIZED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .protocolMethod("initialize")
                                .correlationId(generateCorrelationId())
                                .errorCode(GatewayErrorCode.TRANSPORT_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .build());
        }

        public void auditClientSessionDisconnectedSync(String sessionId,
                        String serverName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_SESSION_DISCONNECTED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientHealthCheckFailed(String sessionId,
                        String serverName,
                        int consecutiveFailures,
                        String reason) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_HEALTH_CHECK_FAILED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.FAILURE)
                                .severity(consecutiveFailures >= 3 ? AuditSeverity.ERROR : AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .errorMessage("Health check failed (attempt " + consecutiveFailures + "): " + reason)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientToolsListFetched(String sessionId,
                        String serverName,
                        int toolCount,
                        Object toolsList,
                        long durationMs) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_TOOLS_LIST_FETCHED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .protocolMethod("tools/list")
                                .capabilityType("TOOL")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of(
                                                "toolCount", toolCount,
                                                "tools", toolsList != null ? toolsList : "[]")))
                                .durationMs(durationMs)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientToolsListFailed(String sessionId,
                        String serverName,
                        String errorMessage,
                        long durationMs) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_TOOLS_LIST_FETCHED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .protocolMethod("tools/list")
                                .capabilityType("TOOL")
                                .correlationId(generateCorrelationId())
                                .errorCode(GatewayErrorCode.INTERNAL_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientResourcesListFetched(String sessionId,
                        String serverName,
                        int resourceCount,
                        Object resourcesList,
                        long durationMs) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_RESOURCES_LIST_FETCHED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .protocolMethod("resources/list")
                                .capabilityType("RESOURCE")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of(
                                                "resourceCount", resourceCount,
                                                "resources", resourcesList != null ? resourcesList : "[]")))
                                .durationMs(durationMs)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientResourcesListFailed(String sessionId,
                        String serverName,
                        String errorMessage,
                        long durationMs) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_RESOURCES_LIST_FETCHED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .protocolMethod("resources/list")
                                .capabilityType("RESOURCE")
                                .correlationId(generateCorrelationId())
                                .errorCode(GatewayErrorCode.INTERNAL_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientPromptsListFetched(String sessionId,
                        String serverName,
                        int promptCount,
                        Object promptsList,
                        long durationMs) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_PROMPTS_LIST_FETCHED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .protocolMethod("prompts/list")
                                .capabilityType("PROMPT")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of(
                                                "promptCount", promptCount,
                                                "prompts", promptsList != null ? promptsList : "[]")))
                                .durationMs(durationMs)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientPromptsListFailed(String sessionId,
                        String serverName,
                        String errorMessage,
                        long durationMs) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_PROMPTS_LIST_FETCHED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .protocolMethod("prompts/list")
                                .capabilityType("PROMPT")
                                .correlationId(generateCorrelationId())
                                .errorCode(GatewayErrorCode.INTERNAL_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientToolInvocation(String sessionId,
                        String correlationId,
                        String serverName,
                        String toolName,
                        Object requestArgs,
                        Object responseContent,
                        long durationMs,
                        LocalDateTime firedAt,
                        Integer eventSequence) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_TOOL_INVOCATION)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(toolName)
                                .capabilityType("TOOL")
                                .protocolMethod("tools/call")
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

        @Async("auditExecutor")
        public void auditClientToolInvocationFailed(String sessionId,
                        String correlationId,
                        String serverName,
                        String toolName,
                        Object requestArgs,
                        String errorMessage,
                        GatewayErrorCode errorCode,
                        long durationMs,
                        LocalDateTime firedAt,
                        Integer eventSequence) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_TOOL_INVOCATION)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(toolName)
                                .capabilityType("TOOL")
                                .protocolMethod("tools/call")
                                .correlationId(correlationId)
                                .requestPayload(toJson(Map.of(
                                                "toolName", toolName,
                                                "arguments", requestArgs != null ? requestArgs : Map.of())))
                                .errorCode(errorCode != null ? errorCode.getCode()
                                                : GatewayErrorCode.INTERNAL_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientResourceRead(String sessionId,
                        String correlationId,
                        String serverName,
                        String resourceUri,
                        Object responseContent,
                        long durationMs,
                        LocalDateTime firedAt,
                        Integer eventSequence) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_RESOURCE_READ)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(resourceUri)
                                .capabilityType("RESOURCE")
                                .protocolMethod("resources/read")
                                .correlationId(correlationId)
                                .requestPayload(toJson(Map.of("uri", resourceUri)))
                                .responsePayload(toJson(Map.of(
                                                "content", responseContent != null ? responseContent : "[]")))
                                .durationMs(durationMs)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientResourceReadFailed(String sessionId,
                        String correlationId,
                        String serverName,
                        String resourceUri,
                        String errorMessage,
                        long durationMs,
                        LocalDateTime firedAt,
                        Integer eventSequence) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_RESOURCE_READ)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .capabilityName(resourceUri)
                                .capabilityType("RESOURCE")
                                .protocolMethod("resources/read")
                                .correlationId(correlationId)
                                .requestPayload(toJson(Map.of("uri", resourceUri)))
                                .errorCode(GatewayErrorCode.INTERNAL_ERROR.getCode())
                                .errorMessage(errorMessage)
                                .durationMs(durationMs)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        @Async("auditExecutor")
        public void auditClientNotificationReceived(String sessionId,
                        String serverName,
                        String notificationType) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.CLIENT_NOTIFICATION_RECEIVED)
                                .module(AuditModule.MCP_OUTBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .protocolMethod(notificationType)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "notification", notificationType,
                                                "serverName", serverName,
                                                "description", "Enterprise MCP server '" + serverName
                                                                + "' sent " + notificationType + " notification")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditServerSessionInitialized(String sessionId,
                        String protocolVersion,
                        Object clientInfo,
                        Object clientCapabilities,
                        String requestId,
                        String agentName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_SESSION_INITIALIZED)
                                .module(AuditModule.MCP_INBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .protocolMethod("initialize")
                                .protocolVersion(protocolVersion)
                                .requestId(requestId)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "clientInfo", clientInfo != null ? clientInfo : Map.of(),
                                                "clientCapabilities",
                                                clientCapabilities != null ? clientCapabilities : Map.of())))
                                .build());
        }

        @Async("auditExecutor")
        public void auditServerToolsListRequested(String sessionId,
                        int toolCount,
                        long durationMs,
                        String requestId,
                        String agentName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_TOOLS_LIST_REQUESTED)
                                .module(AuditModule.MCP_INBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .protocolMethod("tools/list")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of("toolCount", toolCount)))
                                .durationMs(durationMs)
                                .build());
        }

        @Async("auditExecutor")
        public void auditServerResourcesListRequested(String sessionId,
                        int resourceCount,
                        long durationMs,
                        String requestId,
                        String agentName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_RESOURCES_LIST_REQUESTED)
                                .module(AuditModule.MCP_INBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .protocolMethod("resources/list")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of("resourceCount", resourceCount)))
                                .durationMs(durationMs)
                                .build());
        }

        @Async("auditExecutor")
        public void auditServerPromptsListRequested(String sessionId,
                        int promptCount,
                        long durationMs,
                        String requestId,
                        String agentName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_PROMPTS_LIST_REQUESTED)
                                .module(AuditModule.MCP_INBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .protocolMethod("prompts/list")
                                .correlationId(generateCorrelationId())
                                .responsePayload(toJson(Map.of("promptCount", promptCount)))
                                .durationMs(durationMs)
                                .build());
        }

        @Async("auditExecutor")
        public void auditServerNotificationReceived(String sessionId,
                        String method,
                        Object params,
                        String agentName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_NOTIFICATION_RECEIVED)
                                .module(AuditModule.MCP_INBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.DEBUG)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .protocolMethod(method)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "method", method != null ? method : "unknown",
                                                "params", params != null ? params : Map.of())))
                                .build());
        }

        public void auditServerSessionDisconnectedSync(String sessionId, String agentName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_SESSION_DISCONNECTED)
                                .module(AuditModule.MCP_INBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("auditExecutor")
        public void auditServerSessionIdleReaped(String sessionId, String agentName,
                        long idleDurationMs) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_SESSION_IDLE_REAPED)
                                .module(AuditModule.MCP_INBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .correlationId(generateCorrelationId())
                                .durationMs(idleDurationMs)
                                .errorMessage("Session reaped: idle timeout exceeded")
                                .build());
        }

        @Async("auditExecutor")
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

                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_REQUEST_REJECTED)
                                .module(AuditModule.MCP_INBOUND)
                                .status(AuditStatus.DENIED)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .requestId(requestId)
                                .protocolMethod(method)
                                .correlationId(generateCorrelationId())
                                .errorCode(GatewayErrorCode.REQUEST_TIMEOUT.getCode())
                                .errorMessage(errorMessage)
                                .requestPayload(toJson(requestJson))
                                .build());
        }

        @Async("auditExecutor")
        public void auditRegistryCapabilityRegistered(String sessionId,
                        String serverName,
                        String publicName,
                        String capabilityType) {
                persist(GatewayAuditLog.builder()
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

        /**
         * Records that a downstream A2A agent was registered via the admin path — distinct from the per-skill
         * capability events — so the trail unambiguously shows whether the agent was newly onboarded or an
         * already-registered agent whose card was re-ingested. The comment states which, in plain words.
         */
        @Async("auditExecutor")
        public void auditAgentRegistered(String agentName, String baseUrl, String cardName,
                        int skillCount, boolean newlyRegistered) {
                String comment = newlyRegistered
                                ? "New A2A agent '" + agentName + "' registered from " + baseUrl + " — "
                                        + skillCount + " skill(s)."
                                : "A2A agent '" + agentName + "' already registered — endpoint + "
                                        + skillCount + " skill(s) refreshed.";
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AGENT_REGISTERED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .agentName(agentName)
                                .correlationId(generateCorrelationId())
                                .comment(comment)
                                .responsePayload(toJson(Map.of(
                                                "baseUrl", baseUrl == null ? "" : baseUrl,
                                                "cardName", cardName == null ? "" : cardName,
                                                "skillsRegistered", skillCount,
                                                "newlyRegistered", newlyRegistered)))
                                .build());
        }

        @Async("auditExecutor")
        public void auditRegistryCapabilityRemoved(String sessionId,
                        String serverName,
                        String publicName,
                        String capabilityType) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditRegistryBulkLoad(String sessionId,
                        String serverName,
                        int toolCount,
                        int resourceCount,
                        int promptCount,
                        long durationMs) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditRegistryServerRefresh(String sessionId,
                        String serverName,
                        AuditStatus status,
                        String errorMessage,
                        long durationMs) {
                GatewayAuditLog.GatewayAuditLogBuilder builder = GatewayAuditLog.builder()
                                .eventType(AuditEventType.REGISTRY_SERVER_REFRESH)
                                .module(AuditModule.CAPABILITY_REGISTRY)
                                .status(status)
                                .severity(status == AuditStatus.SUCCESS ? AuditSeverity.INFO : AuditSeverity.ERROR)
                                .sessionId(sessionId)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .durationMs(durationMs);

                if (errorMessage != null) {
                        builder.errorCode(GatewayErrorCode.REGISTRY_ERROR.getCode())
                                        .errorMessage(errorMessage);
                }

                persist(builder.build());
        }

        @Async("auditExecutor")
        public void auditNotificationBroadcast(String reason,
                        String serverConfigName,
                        int toolsAdded, int toolsUpdated, int toolsRemoved,
                        int promptsAdded, int promptsUpdated, int promptsRemoved,
                        int resourcesAdded, int resourcesUpdated, int resourcesRemoved,
                        boolean toolsNotified, boolean promptsNotified, boolean resourcesNotified,
                        List<String> notifiedAgents) {
                List<String> notified = new java.util.ArrayList<>();
                if (toolsNotified) notified.add("tools");
                if (promptsNotified) notified.add("prompts");
                if (resourcesNotified) notified.add("resources");

                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.REGISTRY_NOTIFICATION_BROADCAST)
                                .module(AuditModule.CAPABILITY_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .serverName(serverConfigName)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "reason", reason,
                                                "serverConfigName", serverConfigName,
                                                "changes", Map.of(
                                                                "tools", Map.of("added", toolsAdded, "updated", toolsUpdated, "removed", toolsRemoved),
                                                                "prompts", Map.of("added", promptsAdded, "updated", promptsUpdated, "removed", promptsRemoved),
                                                                "resources", Map.of("added", resourcesAdded, "updated", resourcesUpdated, "removed", resourcesRemoved)),
                                                "notificationsSent", notified,
                                                "notifiedAgents", notifiedAgents != null ? notifiedAgents : List.of())))
                                .build());
        }

        @Async("auditExecutor")
        public void auditProfileNotificationBroadcast(String reason,
                        String profileName,
                        java.util.UUID profileId,
                        List<String> affectedAgents,
                        List<String> notifiedAgents) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.REGISTRY_NOTIFICATION_BROADCAST)
                                .module(AuditModule.CAPABILITY_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "reason", reason,
                                                "profileName", profileName,
                                                "profileId", profileId != null ? profileId.toString() : "",
                                                "affectedAgents", affectedAgents != null ? affectedAgents : List.of(),
                                                "notifiedAgents", notifiedAgents != null ? notifiedAgents : List.of(),
                                                "notificationsSent", List.of("tools", "prompts", "resources"))))
                                .build());
        }

        @Async("auditExecutor")
        public void auditOrchestrationToolExtracted(String correlationId,
                        String toolName,
                        String sessionId,
                        String requestId,
                        String agentName,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditOrchestrationRegistryLookup(String correlationId,
                        String sessionId,
                        String publicCapabilityName,
                        String resolvedServerName,
                        long durationMs,
                        String requestId,
                        String agentName,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditOrchestrationCallForwarded(String correlationId,
                        String sessionId,
                        String serverName,
                        String toolName,
                        long durationMs,
                        String requestId,
                        String agentName,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(GatewayAuditLog.builder()
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
                                .protocolMethod("tools/call")
                                .durationMs(durationMs)
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        /** Resolve the tenant for a request: the per-session identity context, else the request-thread tenant. */
        public String resolveTenant(String sessionId) {
                if (sessionId != null) {
                        AuditIdentityContext ctx = sessionIdentityCache.get(sessionId);
                        if (ctx != null && ctx.wsTenantName() != null) {
                                return ctx.wsTenantName();
                        }
                }
                // Session-less data planes (A2A) carry the tenant on the request thread (TenantContext), not a
                // per-session cache — so fall back to it. MCP sessions hit the cache above and are unaffected.
                return TenantContext.get();
        }

        /**
         * Tenant for an async-persisted audit row. The {@code @PrePersist} tenant listener can't fire on the
         * audit executor thread (no {@code TenantContext} there), so resolve it explicitly: the per-session
         * identity cache first, then the request-scoped MDC tenant (captured at submission time). MDC is the
         * safety net for the stateless path, whose per-request identity is evicted as soon as the request
         * returns — before this async row is guaranteed to have persisted.
         */
        private String resolveTenantForAudit(String sessionId) {
                String tenant = resolveTenant(sessionId);
                if (tenant == null) {
                        tenant = org.slf4j.MDC.get("wsTenant");
                }
                return tenant;
        }

        @Async("auditExecutor")
        public void auditStsTokenMinted(String correlationId,
                        String sessionId,
                        String tenant,
                        String serverName,
                        String capabilityName,
                        String capabilityType,
                        MintedToken minted,
                        long ttlSeconds,
                        java.util.List<java.util.Map<String, Object>> actChain,
                        String requestId,
                        LocalDateTime firedAt, Integer eventSequence) {
                // Full token receipt (everything the minted JWT carried, except the raw token itself),
                // so an auditor can reconstruct exactly what was granted, to whom, by which key, for how long.
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("jti", minted.jti());
                payload.put("trace_id", org.slf4j.MDC.get("traceId")); // umbrella (also the trace_id column)
                payload.put("corr_id", correlationId);                 // per-leg (also the correlation_id column)
                payload.put("kid", minted.kid());
                payload.put("alg", minted.alg());
                payload.put("tokenType", "OBO");
                payload.put("iss", minted.issuer());
                payload.put("sub", minted.subject());
                payload.put("aud", minted.audience());
                payload.put("scope", minted.scope());
                payload.put("ttlSeconds", ttlSeconds);
                payload.put("issuedAt", minted.issuedAt() != null ? minted.issuedAt().toString() : null);
                payload.put("expiresAt", minted.expiresAt() != null ? minted.expiresAt().toString() : null);
                payload.put("act_chain", actChain);
                if (actChain != null && !actChain.isEmpty()) {
                        payload.put("actor", actChain.get(actChain.size() - 1));
                }
                payload.put("obo_invariants", minted.oboInvariants()); // Hardening 10 — integrity status, faithful to the token
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.STS_TOKEN_MINTED)
                                .module(AuditModule.STS)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(correlationId)
                                .sessionId(sessionId)
                                .wsTenantName(tenant)
                                .serverName(serverName)
                                .capabilityName(capabilityName)
                                .capabilityType(capabilityType)
                                .protocolMethod("sts/mint")
                                .requestId(requestId)
                                .responsePayload(objectMapper.valueToTree(payload))
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        /**
         * A per-tenant STS signing key was rotated: a fresh ACTIVE key now signs OBO tokens and the previous
         * key(s) were demoted to RETIRING. {@code trigger} is {@code "manual"} (admin) or {@code "auto"} (policy
         * sweep). Non-request-scoped — tenant is passed explicitly (the sweep has no {@code TenantContext}).
         */
        @Async("auditExecutor")
        public void auditStsKeyRotated(String tenant, String newKid,
                        List<String> retiredKids, String trigger) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("newKid", newKid != null ? newKid : "");
                payload.put("retiredKids", retiredKids != null ? retiredKids : List.of());
                payload.put("trigger", trigger != null ? trigger : "manual");
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.STS_KEY_ROTATED)
                                .module(AuditModule.STS)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .wsTenantName(tenant)
                                .capabilityName(newKid)
                                .protocolMethod("sts/keys/rotate")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(payload))
                                .build());
        }

        /**
         * An STS signing key reached the terminal RETIRED state after its JWKS grace window: dropped from the
         * JWKS and its private material scrubbed. One row per retired {@code kid}. Non-request-scoped — the
         * retire sweep has no {@code TenantContext}, so tenant is passed explicitly.
         */
        @Async("auditExecutor")
        public void auditStsKeyRetired(String tenant, String kid) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kid", kid != null ? kid : "");
                payload.put("note", "Dropped from JWKS; private key material scrubbed; kept as bounded rotation history.");
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.STS_KEY_RETIRED)
                                .module(AuditModule.STS)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .wsTenantName(tenant)
                                .capabilityName(kid)
                                .protocolMethod("sts/keys/retire")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(payload))
                                .build());
        }

        @Async("auditExecutor")
        public void auditOrchestrationError(String correlationId,
                        String sessionId,
                        String serverName,
                        String capabilityName,
                        GatewayErrorCode errorCode,
                        String errorMessage,
                        String requestId,
                        String agentName,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
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
                                .wsTenantName(resolveTenantForAudit(sessionId))
                                .pdpSubject(subject)
                                .pdpResource(resource)
                                .pdpAction(action)
                                .pdpContext(toJson(context))
                                .build());

                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.PDP_EVALUATION_REQUESTED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(correlationId)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .capabilityName(resource)
                                .serverName(serverName)
                                .protocolMethod(action)
                                .requestId(requestId)
                                .requestPayload(toJson(context))
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        @Async("auditExecutor")
        public void auditPdpDecisionRendered(String correlationId,
                        String sessionId,
                        String subject,
                        String resource,
                        String action,
                        String decision,
                        String decidedBy,
                        String reason,
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
                                .wsTenantName(resolveTenantForAudit(sessionId))
                                .pdpSubject(subject)
                                .pdpResource(resource)
                                .pdpAction(action)
                                .pdpDecision(decision)
                                // Authoritative deciding-policy + reason live here on the dedicated ledger (covers
                                // every case: matched policy id(s), or the DEFAULT_DENY / NO_POLICIES / EVAL_ERROR marker).
                                .pdpPolicyId(decidedBy != null && decidedBy.length() > 512 ? decidedBy.substring(0, 512) : decidedBy)
                                .pdpReason(reason != null && reason.length() > 1024 ? reason.substring(0, 1024) : reason)
                                .pdpContext(toJson(context))
                                .durationMs(durationMs)
                                .build());

                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.PDP_DECISION_RENDERED)
                                .module(AuditModule.PDP)
                                .status(decisionStatus)
                                .severity(decisionSeverity)
                                .correlationId(correlationId)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .capabilityName(resource)
                                .serverName(serverName)
                                .protocolMethod(action)
                                // Timeline marker only: the decision verb in its proper column (no longer misfiled
                                // into capability_type), for the /logs feed + the activity roll-up. The deciding
                                // policy + reason are NOT persisted here — they live on pdp_audit_log (authoritative)
                                // and are merged onto this row in the chain view at read time.
                                .pdpDecision(decision)
                                .durationMs(durationMs)
                                .requestId(requestId)
                                .responsePayload(toJson(context))
                                .timestamp(firedAt)
                                .eventSequence(eventSequence)
                                .build());
        }

        @Async("auditExecutor")
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

                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
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

                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditPdpPolicyDeleted(String policyName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.PDP_POLICY_DELETED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .capabilityName(policyName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("auditExecutor")
        public void auditPdpPolicyToggled(String policyName, boolean enabled) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditPdpPolicyValidated(String cedarText, boolean valid, String error) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditPdpEngineReloaded(int policyCount) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.PDP_ENGINE_RELOADED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of("policyCount", policyCount)))
                                .build());
        }

        @Async("auditExecutor")
        public void auditPdpLlmChatRequested(String prompt, int messageCount) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditPdpLlmChatCompleted(String prompt, String responseText,
                        long durationMs, boolean success) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditPdpCustomAttrCreated(String attrName, String valueSource, String dataType) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditPdpCustomAttrUpdated(String attrName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.PDP_CUSTOM_ATTR_UPDATED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .capabilityName(attrName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("auditExecutor")
        public void auditPdpCustomAttrDeleted(String attrName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.PDP_CUSTOM_ATTR_DELETED)
                                .module(AuditModule.PDP)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .capabilityName(attrName)
                                .correlationId(generateCorrelationId())
                                .build());
        }

        @Async("auditExecutor")
        public void auditPdpCustomAttrToggled(String attrName, boolean enabled) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditServerConfigCreated(String serverName, String url) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_CONFIG_CREATED)
                                .module(AuditModule.SERVER_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "serverName", serverName,
                                                "url", url != null ? url : "")))
                                .comment("Server '" + serverName + "' created"
                                                + (url != null && !url.isBlank() ? " (url=" + url + ")" : ""))
                                .build());
        }

        @Async("auditExecutor")
        public void auditServerConfigUpdated(String serverName, String url, String comment) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_CONFIG_UPDATED)
                                .module(AuditModule.SERVER_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "serverName", serverName,
                                                "url", url != null ? url : "")))
                                .comment(comment)
                                .build());
        }

        @Async("auditExecutor")
        public void auditServerConfigDeleted(String serverName) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_CONFIG_DELETED)
                                .module(AuditModule.SERVER_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .comment("Server '" + serverName + "' deleted")
                                .build());
        }

        /** One row per admin "Test Connection" (dry-run probe) — outcome + counts in the comment; no capability rows. */
        @Async("auditExecutor")
        public void auditServerConfigTested(String serverName, String url, boolean ok, String comment) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SERVER_CONFIG_TESTED)
                                .module(AuditModule.SERVER_CONFIG)
                                .status(ok ? AuditStatus.SUCCESS : AuditStatus.FAILURE)
                                .severity(ok ? AuditSeverity.INFO : AuditSeverity.WARN)
                                .serverName(serverName)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "serverName", serverName != null ? serverName : "",
                                                "url", url != null ? url : "",
                                                "ok", ok)))
                                .comment(comment)
                                .build());
        }

        public void auditAgentApproved(UUID agentId,
                        String agentName,
                        String agentVersion,
                        String previousApprovalStatus,
                        String adminActor,
                        String adminIp) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AGENT_APPROVED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .agentName(agentName)
                                .protocolMethod("admin/agents/approve")
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
                        String adminIp,
                        int sessionsTerminated,
                        java.util.List<java.util.Map<String, Object>> affectedHumanUsers,
                        java.util.List<java.util.Map<String, Object>> affectedNhis) {
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("agentId", agentId != null ? agentId.toString() : "");
                payload.put("agentName", agentName != null ? agentName : "unknown");
                payload.put("agentVersion", agentVersion != null ? agentVersion : "");
                payload.put("previousApprovalStatus", previousApprovalStatus != null ? previousApprovalStatus : "UNKNOWN");
                payload.put("newApprovalStatus", "BLOCKED");
                payload.put("adminActor", adminActor != null ? adminActor : "unknown");
                payload.put("adminIp", adminIp != null ? adminIp : "unknown");
                payload.put("sessionsTerminated", sessionsTerminated);
                payload.put("affectedHumanUsers", affectedHumanUsers != null ? affectedHumanUsers : java.util.List.of());
                payload.put("affectedNhis", affectedNhis != null ? affectedNhis : java.util.List.of());
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AGENT_BLOCKED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .agentName(agentName)
                                .protocolMethod("admin/agents/block")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(payload))
                                .build());
        }

        public void auditAgentDeprovisioned(UUID agentId,
                        String agentName,
                        String agentVersion,
                        String previousStatus,
                        String adminActor,
                        String adminIp,
                        int sessionsTerminated) {
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("agentId", agentId != null ? agentId.toString() : "");
                payload.put("agentName", agentName != null ? agentName : "unknown");
                payload.put("agentVersion", agentVersion != null ? agentVersion : "");
                payload.put("previousStatus", previousStatus != null ? previousStatus : "UNKNOWN");
                payload.put("newStatus", "DEPROVISIONED");
                payload.put("adminActor", adminActor != null ? adminActor : "unknown");
                payload.put("adminIp", adminIp != null ? adminIp : "unknown");
                payload.put("sessionsTerminated", sessionsTerminated);
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AGENT_DEPROVISIONED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .agentName(agentName)
                                .protocolMethod("admin/agents/deprovision")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(payload))
                                .build());
        }

        public void auditAgentConnectionRejected(String sessionId,
                        String requestId,
                        String agentName,
                        String agentVersion,
                        String method,
                        String transport,
                        String reason) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AGENT_CONNECTION_REJECTED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.DENIED)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .requestId(requestId)
                                .agentName(agentName)
                                .protocolMethod(method != null ? method : "initialize")
                                .correlationId(generateCorrelationId())
                                .errorCode(GatewayErrorCode.AGENT_BLOCKED.getCode())
                                .errorMessage(reason != null ? reason : "Agent rejected by admin policy")
                                .requestPayload(toJson(Map.of(
                                                "agentName", agentName != null ? agentName : "unknown",
                                                "agentVersion", agentVersion != null ? agentVersion : "",
                                                "transport", transport != null ? transport : "HTTP")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditHumanUserBlocked(UUID humanUserId, String preferredUsername, String idpSubject,
                        String previousStatus, String reason, String adminActor, String adminIp,
                        int sessionsTerminated, java.util.List<java.util.Map<String, Object>> affectedAgents) {
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("humanUserId", humanUserId != null ? humanUserId.toString() : "");
                payload.put("preferredUsername", preferredUsername != null ? preferredUsername : "unknown");
                payload.put("idpSubject", idpSubject != null ? idpSubject : "");
                payload.put("previousStatus", previousStatus != null ? previousStatus : "ACTIVE");
                payload.put("newStatus", "BLOCKED");
                payload.put("reason", reason != null ? reason : "");
                payload.put("adminActor", adminActor != null ? adminActor : "unknown");
                payload.put("adminIp", adminIp != null ? adminIp : "unknown");
                payload.put("sessionsTerminated", sessionsTerminated);
                payload.put("affectedAgents", affectedAgents != null ? affectedAgents : java.util.List.of());
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.HUMAN_USER_BLOCKED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .protocolMethod("admin/human-users/block")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(payload))
                                .build());
        }

        @Async("auditExecutor")
        public void auditHumanUserUnblocked(UUID humanUserId, String preferredUsername, String idpSubject,
                        String adminActor, String adminIp) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.HUMAN_USER_UNBLOCKED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .protocolMethod("admin/human-users/unblock")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "humanUserId", humanUserId != null ? humanUserId.toString() : "",
                                                "preferredUsername", preferredUsername != null ? preferredUsername : "unknown",
                                                "idpSubject", idpSubject != null ? idpSubject : "",
                                                "adminActor", adminActor != null ? adminActor : "unknown",
                                                "adminIp", adminIp != null ? adminIp : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditHumanUserApproved(UUID humanUserId, String preferredUsername, String idpSubject,
                        String previousStatus, String adminActor, String adminIp) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.HUMAN_USER_APPROVED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .protocolMethod("admin/human-users/approve")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "humanUserId", humanUserId != null ? humanUserId.toString() : "",
                                                "preferredUsername", preferredUsername != null ? preferredUsername : "unknown",
                                                "idpSubject", idpSubject != null ? idpSubject : "",
                                                "previousStatus", previousStatus != null ? previousStatus : "",
                                                "newStatus", "ACTIVE",
                                                "adminActor", adminActor != null ? adminActor : "unknown",
                                                "adminIp", adminIp != null ? adminIp : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditNhiBlocked(UUID nhiId, String serviceName, String clientId, String idpSubject,
                        String previousStatus, String reason, String adminActor, String adminIp,
                        int sessionsTerminated, java.util.List<java.util.Map<String, Object>> affectedAgents) {
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("nhiId", nhiId != null ? nhiId.toString() : "");
                payload.put("serviceName", serviceName != null ? serviceName : "unknown");
                payload.put("clientId", clientId != null ? clientId : "");
                payload.put("idpSubject", idpSubject != null ? idpSubject : "");
                payload.put("previousStatus", previousStatus != null ? previousStatus : "ACTIVE");
                payload.put("newStatus", "BLOCKED");
                payload.put("reason", reason != null ? reason : "");
                payload.put("adminActor", adminActor != null ? adminActor : "unknown");
                payload.put("adminIp", adminIp != null ? adminIp : "unknown");
                payload.put("sessionsTerminated", sessionsTerminated);
                payload.put("affectedAgents", affectedAgents != null ? affectedAgents : java.util.List.of());
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.NHI_IDENTITY_BLOCKED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .protocolMethod("admin/nhis/block")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(payload))
                                .build());
        }

        @Async("auditExecutor")
        public void auditNhiUnblocked(UUID nhiId, String serviceName, String clientId, String idpSubject,
                        String adminActor, String adminIp) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.NHI_IDENTITY_UNBLOCKED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .protocolMethod("admin/nhis/unblock")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "nhiId", nhiId != null ? nhiId.toString() : "",
                                                "serviceName", serviceName != null ? serviceName : "unknown",
                                                "clientId", clientId != null ? clientId : "",
                                                "idpSubject", idpSubject != null ? idpSubject : "",
                                                "adminActor", adminActor != null ? adminActor : "unknown",
                                                "adminIp", adminIp != null ? adminIp : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditNhiApproved(UUID nhiId, String serviceName, String clientId, String idpSubject,
                        String previousStatus, String adminActor, String adminIp) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.NHI_IDENTITY_APPROVED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .protocolMethod("admin/nhis/approve")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "nhiId", nhiId != null ? nhiId.toString() : "",
                                                "serviceName", serviceName != null ? serviceName : "unknown",
                                                "clientId", clientId != null ? clientId : "",
                                                "idpSubject", idpSubject != null ? idpSubject : "",
                                                "previousStatus", previousStatus != null ? previousStatus : "",
                                                "newStatus", "ACTIVE",
                                                "adminActor", adminActor != null ? adminActor : "unknown",
                                                "adminIp", adminIp != null ? adminIp : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditHumanConnectionRejected(String sessionId, String requestId,
                        String preferredUsername, String idpSubject,
                        String agentName, String method, String reason) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.HUMAN_CONNECTION_REJECTED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.DENIED)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .requestId(requestId)
                                .agentName(agentName)
                                .protocolMethod(method != null ? method : "unknown")
                                .errorCode(GatewayErrorCode.HUMAN_BLOCKED.getCode())
                                .errorMessage(reason)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "preferredUsername", preferredUsername != null ? preferredUsername : "unknown",
                                                "idpSubject", idpSubject != null ? idpSubject : "",
                                                "agentName", agentName != null ? agentName : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditNhiConnectionRejected(String sessionId, String requestId,
                        String serviceName, String clientId, String idpSubject,
                        String agentName, String method, String reason) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.NHI_CONNECTION_REJECTED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.DENIED)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .requestId(requestId)
                                .agentName(agentName)
                                .protocolMethod(method != null ? method : "unknown")
                                .errorCode(GatewayErrorCode.NHI_BLOCKED.getCode())
                                .errorMessage(reason)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "serviceName", serviceName != null ? serviceName : "unknown",
                                                "clientId", clientId != null ? clientId : "",
                                                "idpSubject", idpSubject != null ? idpSubject : "",
                                                "agentName", agentName != null ? agentName : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditBlockedSessionTerminated(String sessionId, String agentName,
                        String identityType, String identityName, String reason) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.BLOCKED_SESSION_TERMINATED)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .agentName(agentName)
                                .protocolMethod("admin/block/session-terminate")
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "identityType", identityType != null ? identityType : "unknown",
                                                "identityName", identityName != null ? identityName : "unknown",
                                                "reason", reason != null ? reason : "Identity blocked by admin")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditSessionIdentityMismatch(String sessionId, String agentName,
                        String foundingSubject, String currentSubject,
                        String currentClientId, String currentUsername, String currentEmail,
                        String currentTokenType, String currentIssuer,
                        java.util.List<String> currentRoles,
                        String remoteAddr, String userAgent, String requestId) {

                Map<String, Object> forensics = new java.util.LinkedHashMap<>();
                forensics.put("incident", "SESSION_IDENTITY_MISMATCH");
                forensics.put("sessionId", sessionId != null ? sessionId : "");

                Map<String, Object> legitimateOwner = new java.util.LinkedHashMap<>();
                legitimateOwner.put("jwtSubject", foundingSubject != null ? foundingSubject : "");
                forensics.put("legitimateOwner", legitimateOwner);

                Map<String, Object> intruder = new java.util.LinkedHashMap<>();
                intruder.put("jwtSubject", currentSubject != null ? currentSubject : "");
                intruder.put("clientId", currentClientId != null ? currentClientId : "");
                intruder.put("preferredUsername", currentUsername != null ? currentUsername : "");
                intruder.put("email", currentEmail != null ? currentEmail : "");
                intruder.put("tokenType", currentTokenType != null ? currentTokenType : "");
                intruder.put("idpIssuer", currentIssuer != null ? currentIssuer : "");
                intruder.put("roles", currentRoles != null ? currentRoles : java.util.List.of());
                forensics.put("intruder", intruder);

                Map<String, Object> network = new java.util.LinkedHashMap<>();
                network.put("remoteAddress", remoteAddr != null ? remoteAddr : "");
                network.put("userAgent", userAgent != null ? userAgent : "");
                forensics.put("network", network);

                forensics.put("timestamp", java.time.LocalDateTime.now().toString());
                forensics.put("action", "REQUEST_REJECTED");

                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.SESSION_IDENTITY_MISMATCH)
                                .module(AuditModule.AGENT_REGISTRY)
                                .status(AuditStatus.DENIED)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .requestId(requestId)
                                .agentName(agentName)
                                .agentClientId(currentClientId)
                                .authIdentity(currentSubject)
                                .userIdentity(currentUsername)
                                .tokenType(currentTokenType)
                                .agentRoles(currentRoles)
                                .correlationId(generateCorrelationId())
                                .errorCode(-32001)
                                .errorMessage("Session identity mismatch: founding=" + foundingSubject
                                        + ", intruder=" + currentSubject + ", ip=" + remoteAddr)
                                .requestPayload(toJson(forensics))
                                .build());
        }

        @Async("auditExecutor")
        public void auditCapabilityProfileCreated(String profileName, UUID profileId,
                        String description, int ruleCount) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditCapabilityProfileUpdated(String profileName, UUID profileId,
                        String changedFields) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditCapabilityProfileDeleted(String profileName, UUID profileId) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditCapabilityProfileAssigned(String profileName, UUID profileId,
                        String agentName, UUID agentId) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditCapabilityProfileUnassigned(String profileName, UUID profileId,
                        String agentName, UUID agentId) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditCapabilityAccessDenied(String sessionId, String correlationId,
                        String agentName, String capabilityName, String capabilityType,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditCapabilityAccessGranted(String sessionId, String correlationId,
                        String agentName, String capabilityName, String capabilityType,
                        LocalDateTime firedAt, Integer eventSequence) {
                persist(GatewayAuditLog.builder()
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

        @Async("auditExecutor")
        public void auditOAuth2AuthSuccess(String sessionId, String agentClientId, String subject,
                        List<String> roles, String tokenType, String userIdentity,
                        Map<String, Object> rawClaims, String requestId) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.OAUTH2_AUTH_SUCCESS)
                                .module(AuditModule.MCP_INBOUND)
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

        @Async("auditExecutor")
        public void auditTokenClassificationOverride(
                        String sessionId, String agentName,
                        String jwtSignalType, String jwtSignal,
                        String introspectionType, String introspectionSignal,
                        String requestId) {
                String message = String.format(
                                "Introspection overrode JWT signal: %s (%s) → %s (%s)",
                                jwtSignalType, jwtSignal, introspectionType, introspectionSignal);
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.OAUTH2_TOKEN_CLASSIFICATION_OVERRIDE)
                                .module(AuditModule.MCP_INBOUND)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .sessionId(sessionId)
                                .requestId(requestId)
                                .agentName(agentName)
                                .tokenType(introspectionType)
                                .errorMessage(message)
                                .build());
        }

        private void persist(GatewayAuditLog auditLog) {
                try {
                        if (auditLog.getTimestamp() == null) {
                                auditLog.setTimestamp(LocalDateTime.now());
                        }

                        // Stamp the request trace id from MDC (propagated into this async thread), so every
                        // audit event of a request shares one trace_id — the whole-journey umbrella.
                        if (auditLog.getTraceId() == null) {
                                String traceId = org.slf4j.MDC.get("traceId");
                                if (traceId != null) {
                                        auditLog.setTraceId(traceId);
                                }
                        }

                        if (auditLog.getSessionId() != null) {
                                AuditIdentityContext ctx = sessionIdentityCache.get(auditLog.getSessionId());
                                if (ctx != null) {
                                        if (auditLog.getTokenType() == null) auditLog.setTokenType(ctx.tokenType());
                                        if (auditLog.getUserIdentity() == null) auditLog.setUserIdentity(ctx.userIdentity());
                                        if (auditLog.getHumanUserId() == null) auditLog.setHumanUserId(ctx.humanUserId());
                                        if (auditLog.getNhiId() == null) auditLog.setNhiId(ctx.nhiId());
                                        if (auditLog.getAuthMethod() == null) auditLog.setAuthMethod(ctx.authMethod());
                                        if (auditLog.getAuthIdentity() == null) auditLog.setAuthIdentity(ctx.authIdentity());
                                        if (auditLog.getAgentClientId() == null) auditLog.setAgentClientId(ctx.agentClientId());
                                        if (auditLog.getAgentRoles() == null) auditLog.setAgentRoles(ctx.agentRoles());
                                        if (auditLog.getSourceIp() == null) auditLog.setSourceIp(ctx.sourceIp());
                                        if (auditLog.getWsTenantName() == null && ctx.wsTenantName() != null) {
                                                auditLog.setWsTenantName(ctx.wsTenantName());
                                        }
                                }
                        }

                        if (auditLog.getWsTenantName() == null) {
                                String tenant = TenantContext.get();
                                if (tenant == null) {
                                        // Request-scoped tenant captured into MDC at submission time — survives the
                                        // stateless per-request identity eviction the long-lived session cache doesn't.
                                        tenant = org.slf4j.MDC.get("wsTenant");
                                }
                                auditLog.setWsTenantName(tenant != null ? tenant : "system");
                        }

                        // Stamp the leg's protocol (A2A vs MCP) from MDC so the dashboard can filter/badge by protocol.
                        String legProtocol = org.slf4j.MDC.get("protocol");
                        if (legProtocol != null && !legProtocol.isBlank()) {
                        	auditLog.setProtocol(legProtocol);
                        }
                        repository.save(auditLog);
                        log.debug("Audit [{}] {} — {} | server={} capability={}",
                                        auditLog.getModule(),
                                        auditLog.getEventType(),
                                        auditLog.getStatus(),
                                        auditLog.getServerName(),
                                        auditLog.getCapabilityName());
                } catch (Exception e) {
                        log.error("Failed to persist audit log [{}] {}: {}",
                                        auditLog.getModule(), auditLog.getEventType(), e.getMessage(), e);
                }
        }

        private void persistPdp(PdpAuditLog pdpLog) {
                try {
                        if (pdpLog.getWsTenantName() == null) {
                                // Last-resort guard: the @PrePersist tenant listener can't fire on the async audit
                                // thread and pdp_audit_log.ws_tenant_name is NOT NULL — never let a null crash the row.
                                pdpLog.setWsTenantName("system");
                        }
                        pdpRepository.save(pdpLog);
                        log.debug("PDP Audit [{}] {} — {} | subject={} resource={} decision={}",
                                        pdpLog.getEventType(),
                                        pdpLog.getStatus(),
                                        pdpLog.getCorrelationId(),
                                        pdpLog.getPdpSubject(),
                                        pdpLog.getPdpResource(),
                                        pdpLog.getPdpDecision());
                } catch (Exception e) {
                        log.error("Failed to persist PDP audit log [{}] {}: {}",
                                        pdpLog.getEventType(), pdpLog.getCorrelationId(), e.getMessage(), e);
                }
        }

        @Async("auditExecutor")
        public void auditAuthConfigCreated(String authMode, String issuerUri, String idpName,
                        String classificationMode, String adminIdentity, String sourceIp) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AUTH_CONFIG_CREATED)
                                .module(AuditModule.AUTH_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .sourceIp(sourceIp)
                                .requestPayload(toJson(Map.of(
                                                "authMode", authMode != null ? authMode : "",
                                                "issuerUri", issuerUri != null ? issuerUri : "",
                                                "idpDisplayName", idpName != null ? idpName : "",
                                                "tokenClassificationMode", classificationMode != null ? classificationMode : "",
                                                "adminIdentity", adminIdentity != null ? adminIdentity : "unknown",
                                                "sourceIp", sourceIp != null ? sourceIp : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditAuthConfigUpdated(Map<String, Object> changedFields,
                        String adminIdentity, String sourceIp) {
                Map<String, Object> payload = new java.util.LinkedHashMap<>(changedFields);
                payload.put("adminIdentity", adminIdentity != null ? adminIdentity : "unknown");
                payload.put("sourceIp", sourceIp != null ? sourceIp : "unknown");
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AUTH_CONFIG_UPDATED)
                                .module(AuditModule.AUTH_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .sourceIp(sourceIp)
                                .requestPayload(toJson(payload))
                                .build());
        }

        @Async("auditExecutor")
        public void auditAuthConfigDeleted(String authMode, String issuerUri,
                        String adminIdentity, String sourceIp) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AUTH_CONFIG_DELETED)
                                .module(AuditModule.AUTH_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .correlationId(generateCorrelationId())
                                .sourceIp(sourceIp)
                                .requestPayload(toJson(Map.of(
                                                "deletedAuthMode", authMode != null ? authMode : "",
                                                "deletedIssuerUri", issuerUri != null ? issuerUri : "",
                                                "adminIdentity", adminIdentity != null ? adminIdentity : "unknown",
                                                "sourceIp", sourceIp != null ? sourceIp : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditAuthModeChanged(String previousMode, String newMode,
                        int activeSessionCount, String adminIdentity, String sourceIp) {
                AuditSeverity severity = "none".equals(newMode) && "oauth2".equals(previousMode)
                                ? AuditSeverity.ERROR
                                : AuditSeverity.WARN;
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AUTH_MODE_CHANGED)
                                .module(AuditModule.AUTH_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(severity)
                                .correlationId(generateCorrelationId())
                                .sourceIp(sourceIp)
                                .requestPayload(toJson(Map.of(
                                                "previousMode", previousMode != null ? previousMode : "",
                                                "newMode", newMode != null ? newMode : "",
                                                "activeSessionCount", activeSessionCount,
                                                "adminIdentity", adminIdentity != null ? adminIdentity : "unknown",
                                                "sourceIp", sourceIp != null ? sourceIp : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditAuthConfigValidated(String issuerUri, boolean jwksReachable,
                        long latencyMs, int keyCount, String adminIdentity, String sourceIp) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AUTH_CONFIG_VALIDATED)
                                .module(AuditModule.AUTH_CONFIG)
                                .status(jwksReachable ? AuditStatus.SUCCESS : AuditStatus.FAILURE)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .sourceIp(sourceIp)
                                .durationMs(latencyMs)
                                .requestPayload(toJson(Map.of(
                                                "issuerUri", issuerUri != null ? issuerUri : "",
                                                "jwksReachable", jwksReachable,
                                                "latencyMs", latencyMs,
                                                "jwksKeyCount", keyCount,
                                                "adminIdentity", adminIdentity != null ? adminIdentity : "unknown",
                                                "sourceIp", sourceIp != null ? sourceIp : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditAuthConfigValidationFailed(String issuerUri, String errorMessage,
                        int httpStatus, long timeoutMs, String adminIdentity, String sourceIp) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AUTH_CONFIG_VALIDATION_FAILED)
                                .module(AuditModule.AUTH_CONFIG)
                                .status(AuditStatus.FAILURE)
                                .severity(AuditSeverity.WARN)
                                .correlationId(generateCorrelationId())
                                .sourceIp(sourceIp)
                                .durationMs(timeoutMs)
                                .errorMessage(errorMessage)
                                .errorCode(httpStatus)
                                .requestPayload(toJson(Map.of(
                                                "issuerUri", issuerUri != null ? issuerUri : "",
                                                "errorMessage", errorMessage != null ? errorMessage : "",
                                                "httpStatus", httpStatus,
                                                "timeoutMs", timeoutMs,
                                                "adminIdentity", adminIdentity != null ? adminIdentity : "unknown",
                                                "sourceIp", sourceIp != null ? sourceIp : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditAuthJwksRefreshed(String issuerUri, int previousKeyCount,
                        int newKeyCount, String triggeredBy) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AUTH_JWKS_REFRESHED)
                                .module(AuditModule.AUTH_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.INFO)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "issuerUri", issuerUri != null ? issuerUri : "",
                                                "previousKeyCount", previousKeyCount,
                                                "newKeyCount", newKeyCount,
                                                "triggeredBy", triggeredBy != null ? triggeredBy : "unknown")))
                                .build());
        }

        @Async("auditExecutor")
        public void auditAuthGracePeriodStarted(String previousIssuer, String newIssuer,
                        int gracePeriodMinutes, int activeSessionCount) {
                persist(GatewayAuditLog.builder()
                                .eventType(AuditEventType.AUTH_GRACE_PERIOD_STARTED)
                                .module(AuditModule.AUTH_CONFIG)
                                .status(AuditStatus.SUCCESS)
                                .severity(AuditSeverity.WARN)
                                .correlationId(generateCorrelationId())
                                .requestPayload(toJson(Map.of(
                                                "previousIssuer", previousIssuer != null ? previousIssuer : "",
                                                "newIssuer", newIssuer != null ? newIssuer : "",
                                                "gracePeriodMinutes", gracePeriodMinutes,
                                                "activeSessionCount", activeSessionCount)))
                                .build());
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
