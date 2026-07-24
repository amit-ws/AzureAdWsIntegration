package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayCustomAttributeEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayCustomAttributeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Service
@Slf4j
public class CustomAttributeService {

    private static final long CACHE_TTL_MS = 30_000;
    private static final Pattern VALID_ATTR_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");
    private static final Set<String> VALID_DATA_TYPES = Set.of("STRING", "INTEGER", "BOOLEAN");
    private static final Set<String> VALID_VALUE_SOURCES = Set.of("STATIC", "HEADER", "AGENT_FIELD");
    private static final Set<String> VALID_AGENT_FIELDS = Set.of(
            "totalRequests", "totalSessions", "status", "approvalStatus",
            "protocolVersion", "firstSeenAt", "lastSeenAt", "agentVersion"
    );

    private final GatewayCustomAttributeRepository repository;
    private final AgentRegistryService agentRegistryService;
    private final McpAuditService auditService;

    private volatile List<GatewayCustomAttributeEntity> cachedAttributes;
    private volatile long cacheTimestamp = 0;

    public CustomAttributeService(GatewayCustomAttributeRepository repository,
                                   AgentRegistryService agentRegistryService,
                                   McpAuditService auditService) {
        this.repository = repository;
        this.agentRegistryService = agentRegistryService;
        this.auditService = auditService;
    }

    public List<GatewayCustomAttributeEntity> getAll() {
        return repository.findAllByWsTenantName(TenantContext.get());
    }

    public Optional<GatewayCustomAttributeEntity> getById(UUID id) {
        return repository.findById(id);
    }

    public List<GatewayCustomAttributeEntity> getEnabledAttributes() {
        return getCachedAttributes();
    }

    @Transactional
    public AttributeResult create(GatewayCustomAttributeEntity attr) {
        String error = validate(attr, false);
        if (error != null) {
            return AttributeResult.error(error);
        }

        attr.setDataType(attr.getDataType().toUpperCase());
        attr.setValueSource(attr.getValueSource().toUpperCase());
        if (attr.getEnabled() == null) {
            attr.setEnabled(true);
        }
        attr.setWsTenantName(TenantContext.get());

        GatewayCustomAttributeEntity saved = repository.save(attr);
        invalidateCache();
        log.info("Created custom attribute: {} (source={}, type={})",
                saved.getAttributeName(), saved.getValueSource(), saved.getDataType());
        auditService.auditPdpCustomAttrCreated(saved.getAttributeName(),
                saved.getValueSource(), saved.getDataType());
        return AttributeResult.success(saved);
    }

    @Transactional
    public AttributeResult update(UUID id, GatewayCustomAttributeEntity updates) {
        Optional<GatewayCustomAttributeEntity> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return AttributeResult.error("Attribute not found: " + id);
        }

        GatewayCustomAttributeEntity attr = existing.get();

        if (updates.getDisplayName() != null) attr.setDisplayName(updates.getDisplayName());
        if (updates.getDescription() != null) attr.setDescription(updates.getDescription());
        if (updates.getDefaultValue() != null) attr.setDefaultValue(updates.getDefaultValue());

        if (updates.getDataType() != null) {
            String dt = updates.getDataType().toUpperCase();
            if (!VALID_DATA_TYPES.contains(dt)) {
                return AttributeResult.error("Invalid dataType: " + dt + ". Must be one of: " + VALID_DATA_TYPES);
            }
            attr.setDataType(dt);
        }

        if (updates.getValueSource() != null) {
            String vs = updates.getValueSource().toUpperCase();
            if (!VALID_VALUE_SOURCES.contains(vs)) {
                return AttributeResult.error("Invalid valueSource: " + vs + ". Must be one of: " + VALID_VALUE_SOURCES);
            }
            attr.setValueSource(vs);
        }

        if (updates.getSourceKey() != null) {
            attr.setSourceKey(updates.getSourceKey());
        }

        String error = validateSourceKey(attr);
        if (error != null) {
            return AttributeResult.error(error);
        }

        GatewayCustomAttributeEntity saved = repository.save(attr);
        invalidateCache();
        log.info("Updated custom attribute: {}", saved.getAttributeName());
        auditService.auditPdpCustomAttrUpdated(saved.getAttributeName());
        return AttributeResult.success(saved);
    }

    @Transactional
    public boolean delete(UUID id) {
        Optional<GatewayCustomAttributeEntity> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return false;
        }
        String attrName = existing.get().getAttributeName();
        repository.deleteById(id);
        invalidateCache();
        log.info("Deleted custom attribute: {} ({})", attrName, id);
        auditService.auditPdpCustomAttrDeleted(attrName);
        return true;
    }

    @Transactional
    public Optional<GatewayCustomAttributeEntity> toggleEnabled(UUID id) {
        return repository.findById(id).map(attr -> {
            attr.setEnabled(!attr.getEnabled());
            GatewayCustomAttributeEntity saved = repository.save(attr);
            invalidateCache();
            log.info("Toggled custom attribute '{}' to enabled={}",
                    saved.getAttributeName(), saved.getEnabled());
            auditService.auditPdpCustomAttrToggled(saved.getAttributeName(), saved.getEnabled());
            return saved;
        });
    }

    public Map<String, Object> getStats() {
        String tenant = TenantContext.get();
        Map<String, Object> stats = new LinkedHashMap<>();
        if (tenant != null) {
            stats.put("totalAttributes", repository.findAllByWsTenantName(tenant).size());
            stats.put("enabledAttributes", repository.countByEnabledTrueAndWsTenantName(tenant));
        } else {
            stats.put("totalAttributes", repository.count());
            stats.put("enabledAttributes", repository.countByEnabledTrue());
        }
        return stats;
    }

    public Map<String, Object> resolveAttributes(Map<String, String> httpHeaders,
                                                   Map<String, Object> transportContext,
                                                   String agentName) {
        List<GatewayCustomAttributeEntity> attrs = getCachedAttributes();
        if (attrs.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (GatewayCustomAttributeEntity attr : attrs) {
            try {
                Object value = resolveValue(attr, httpHeaders, transportContext, agentName);
                if (value != null) {
                    resolved.put(attr.getAttributeName(), coerce(value, attr.getDataType()));
                }
            } catch (Exception e) {
                log.debug("Failed to resolve custom attribute '{}': {}",
                        attr.getAttributeName(), e.getMessage());
            }
        }
        return resolved;
    }

    private Object resolveValue(GatewayCustomAttributeEntity attr,
                                 Map<String, String> httpHeaders,
                                 Map<String, Object> transportContext,
                                 String agentName) {
        Object value = null;

        switch (attr.getValueSource()) {
            case "STATIC" -> value = attr.getDefaultValue();

            case "HEADER" -> {
                String key = attr.getSourceKey();
                if (key == null) break;

                if (httpHeaders != null) {
                    value = httpHeaders.get(key);
                    if (value == null) {
                        for (Map.Entry<String, String> entry : httpHeaders.entrySet()) {
                            if (entry.getKey().equalsIgnoreCase(key)) {
                                value = entry.getValue();
                                break;
                            }
                        }
                    }
                }

                if (value == null && transportContext != null) {
                    Object tcVal = transportContext.get(key);
                    if (tcVal != null) {
                        value = String.valueOf(tcVal);
                    }
                }
            }

            case "AGENT_FIELD" -> {
                value = resolveAgentField(agentName, attr.getSourceKey());
            }
        }

        if (value == null && attr.getDefaultValue() != null) {
            value = attr.getDefaultValue();
        }

        return value;
    }

    private Object resolveAgentField(String agentName, String fieldName) {
        if (agentName == null || fieldName == null) return null;
        try {
            List<GatewayAgentEntity> agents = agentRegistryService.findAgentsByName(agentName);
            if (agents == null || agents.isEmpty()) return null;
            GatewayAgentEntity agent = agents.get(0);
            return switch (fieldName) {
                case "totalRequests" -> agent.getTotalRequests();
                case "totalSessions" -> agent.getTotalSessions();
                case "status" -> agent.getStatus();
                case "approvalStatus" -> agent.getApprovalStatus();
                case "protocolVersion" -> agent.getProtocolVersion();
                case "agentVersion" -> agent.getAgentVersion();
                case "firstSeenAt" -> agent.getFirstSeenAt() != null
                        ? agent.getFirstSeenAt().toString() : null;
                case "lastSeenAt" -> agent.getLastSeenAt() != null
                        ? agent.getLastSeenAt().toString() : null;
                default -> {
                    log.debug("Unknown agent field '{}' requested for attribute resolution", fieldName);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("Failed to resolve agent field '{}' for agent '{}': {}",
                    fieldName, agentName, e.getMessage());
            return null;
        }
    }

    private Object coerce(Object value, String dataType) {
        if (value == null) return null;
        String str = String.valueOf(value);
        return switch (dataType) {
            case "INTEGER" -> {
                try {
                    yield Long.parseLong(str);
                } catch (NumberFormatException e) {
                    log.debug("Could not coerce '{}' to INTEGER, keeping as string", str);
                    yield str;
                }
            }
            case "BOOLEAN" -> Boolean.parseBoolean(str);
            default -> str;
        };
    }

    private List<GatewayCustomAttributeEntity> getCachedAttributes() {
        long now = System.currentTimeMillis();
        if (cachedAttributes != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedAttributes;
        }
        synchronized (this) {
            if (cachedAttributes != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS) {
                return cachedAttributes;
            }
            cachedAttributes = repository.findByEnabledTrueOrderByAttributeNameAsc();
            cacheTimestamp = System.currentTimeMillis();
            return cachedAttributes;
        }
    }

    private void invalidateCache() {
        cachedAttributes = null;
        cacheTimestamp = 0;
    }

    private String validate(GatewayCustomAttributeEntity attr, boolean isUpdate) {
        if (attr.getAttributeName() == null || attr.getAttributeName().isBlank()) {
            return "attributeName is required";
        }
        if (!VALID_ATTR_NAME.matcher(attr.getAttributeName()).matches()) {
            return "attributeName must be alphanumeric with underscores, starting with a letter (e.g., 'riskScore', 'department_code')";
        }
        if (!isUpdate && repository.existsByAttributeNameAndWsTenantName(attr.getAttributeName(), TenantContext.get())) {
            return "Attribute '" + attr.getAttributeName() + "' already exists";
        }
        if (attr.getDataType() == null || !VALID_DATA_TYPES.contains(attr.getDataType().toUpperCase())) {
            return "dataType is required and must be one of: " + VALID_DATA_TYPES;
        }
        if (attr.getValueSource() == null || !VALID_VALUE_SOURCES.contains(attr.getValueSource().toUpperCase())) {
            return "valueSource is required and must be one of: " + VALID_VALUE_SOURCES;
        }

        return validateSourceKey(attr);
    }

    private String validateSourceKey(GatewayCustomAttributeEntity attr) {
        String source = attr.getValueSource() != null ? attr.getValueSource().toUpperCase() : "";
        switch (source) {
            case "STATIC" -> {
                if (attr.getDefaultValue() == null || attr.getDefaultValue().isBlank()) {
                    return "defaultValue is required for STATIC value source";
                }
            }
            case "HEADER" -> {
                if (attr.getSourceKey() == null || attr.getSourceKey().isBlank()) {
                    return "sourceKey (HTTP header name) is required for HEADER value source";
                }
            }
            case "AGENT_FIELD" -> {
                if (attr.getSourceKey() == null || attr.getSourceKey().isBlank()) {
                    return "sourceKey (agent field name) is required for AGENT_FIELD value source";
                }
                if (!VALID_AGENT_FIELDS.contains(attr.getSourceKey())) {
                    return "sourceKey must be a valid agent field: " + VALID_AGENT_FIELDS;
                }
            }
        }
        return null;
    }

    public record AttributeResult(boolean success, GatewayCustomAttributeEntity attribute, String error) {
        public static AttributeResult success(GatewayCustomAttributeEntity attr) {
            return new AttributeResult(true, attr, null);
        }

        public static AttributeResult error(String error) {
            return new AttributeResult(false, null, error);
        }
    }
}
