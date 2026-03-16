package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayCustomAttributeEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayCustomAttributeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Custom Attribute lifecycle management and runtime resolution.
 *
 * <h3>Two responsibilities</h3>
 * <ol>
 *   <li><b>Admin CRUD</b> — register, update, toggle, delete custom attributes via REST API</li>
 *   <li><b>Runtime resolution</b> — at policy evaluation time, resolve all enabled attributes
 *       from their configured sources (STATIC, HEADER, AGENT_FIELD) and return as a flat map
 *       that merges into {@code contextAttrs} in the Cedar engine</li>
 * </ol>
 *
 * <h3>Caching</h3>
 * <p>Attribute definitions are cached for 30 seconds to avoid per-request DB queries.
 * Any mutation (create/update/delete/toggle) invalidates the cache immediately.
 */
@Service
@Slf4j
public class CustomAttributeService {

    private static final long CACHE_TTL_MS = 30_000; // 30 seconds
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

    // ── Cache ──────────────────────────────────────────────────────────────
    private volatile List<GatewayCustomAttributeEntity> cachedAttributes;
    private volatile long cacheTimestamp = 0;

    public CustomAttributeService(GatewayCustomAttributeRepository repository,
                                   AgentRegistryService agentRegistryService,
                                   McpAuditService auditService) {
        this.repository = repository;
        this.agentRegistryService = agentRegistryService;
        this.auditService = auditService;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CRUD
    // ════════════════════════════════════════════════════════════════════════

    /** List all custom attributes. */
    public List<GatewayCustomAttributeEntity> getAll() {
        return repository.findAll();
    }

    /** Get a specific attribute by ID. */
    public Optional<GatewayCustomAttributeEntity> getById(UUID id) {
        return repository.findById(id);
    }

    /** Get a specific attribute by name. */
    public Optional<GatewayCustomAttributeEntity> getByName(String attributeName) {
        return repository.findByAttributeName(attributeName);
    }

    /** Get all enabled attributes (for LLM metadata / runtime resolution). */
    public List<GatewayCustomAttributeEntity> getEnabledAttributes() {
        return getCachedAttributes();
    }

    /**
     * Create a new custom attribute.
     *
     * @return the created entity, or null if validation fails (error in second element)
     */
    @Transactional
    public AttributeResult create(GatewayCustomAttributeEntity attr) {
        String error = validate(attr, false);
        if (error != null) {
            return AttributeResult.error(error);
        }

        // Normalize
        attr.setDataType(attr.getDataType().toUpperCase());
        attr.setValueSource(attr.getValueSource().toUpperCase());
        if (attr.getEnabled() == null) {
            attr.setEnabled(true);
        }

        GatewayCustomAttributeEntity saved = repository.save(attr);
        invalidateCache();
        log.info("Created custom attribute: {} (source={}, type={})",
                saved.getAttributeName(), saved.getValueSource(), saved.getDataType());
        auditService.auditPdpCustomAttrCreated(saved.getAttributeName(),
                saved.getValueSource(), saved.getDataType());
        return AttributeResult.success(saved);
    }

    /**
     * Update an existing custom attribute.
     */
    @Transactional
    public AttributeResult update(UUID id, GatewayCustomAttributeEntity updates) {
        Optional<GatewayCustomAttributeEntity> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return AttributeResult.error("Attribute not found: " + id);
        }

        GatewayCustomAttributeEntity attr = existing.get();

        // Patch non-null fields
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

        // Re-validate
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

    /**
     * Delete a custom attribute.
     */
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

    /**
     * Toggle enabled/disabled state.
     */
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

    /** Stats for dashboard. */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalAttributes", repository.count());
        stats.put("enabledAttributes", repository.countByEnabledTrue());
        return stats;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  RUNTIME RESOLUTION (called per policy evaluation)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Resolve all enabled custom attributes for the current request.
     *
     * <p>For each registered attribute, resolves its value from the configured source:
     * <ul>
     *   <li>{@code STATIC} → returns the default value</li>
     *   <li>{@code HEADER} → looks up the header in the transport context / HTTP headers</li>
     *   <li>{@code AGENT_FIELD} → looks up the field on the agent's DB record</li>
     * </ul>
     *
     * <p>If a source returns null, the defaultValue is used as fallback.
     * Values are coerced to the declared data type (INTEGER, BOOLEAN, or STRING).
     *
     * @param httpHeaders   all HTTP headers from the request (case-insensitive lookup)
     * @param transportContext known transport context entries (clientIp, agentName, etc.)
     * @param agentName     the requesting agent's name (for AGENT_FIELD lookups)
     * @return flat map of attribute names → resolved values, ready for contextAttrs merge
     */
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

    // ════════════════════════════════════════════════════════════════════════
    //  PRIVATE — resolution helpers
    // ════════════════════════════════════════════════════════════════════════

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

                // 1. Try HTTP headers (case-insensitive)
                if (httpHeaders != null) {
                    value = httpHeaders.get(key);
                    if (value == null) {
                        // Case-insensitive fallback
                        for (Map.Entry<String, String> entry : httpHeaders.entrySet()) {
                            if (entry.getKey().equalsIgnoreCase(key)) {
                                value = entry.getValue();
                                break;
                            }
                        }
                    }
                }

                // 2. Fallback to transport context (for keys put there explicitly)
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

        // Fall back to default value if source returned nothing
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

    /**
     * Coerce a raw value to the declared data type.
     */
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
            default -> str; // STRING
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PRIVATE — cache
    // ════════════════════════════════════════════════════════════════════════

    private List<GatewayCustomAttributeEntity> getCachedAttributes() {
        long now = System.currentTimeMillis();
        if (cachedAttributes != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedAttributes;
        }
        synchronized (this) {
            // Double-check
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

    // ════════════════════════════════════════════════════════════════════════
    //  PRIVATE — validation
    // ════════════════════════════════════════════════════════════════════════

    private String validate(GatewayCustomAttributeEntity attr, boolean isUpdate) {
        if (attr.getAttributeName() == null || attr.getAttributeName().isBlank()) {
            return "attributeName is required";
        }
        if (!VALID_ATTR_NAME.matcher(attr.getAttributeName()).matches()) {
            return "attributeName must be alphanumeric with underscores, starting with a letter (e.g., 'riskScore', 'department_code')";
        }
        if (!isUpdate && repository.existsByAttributeName(attr.getAttributeName())) {
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

    // ════════════════════════════════════════════════════════════════════════
    //  RESULT RECORD
    // ════════════════════════════════════════════════════════════════════════

    public record AttributeResult(boolean success, GatewayCustomAttributeEntity attribute, String error) {
        public static AttributeResult success(GatewayCustomAttributeEntity attr) {
            return new AttributeResult(true, attr, null);
        }

        public static AttributeResult error(String error) {
            return new AttributeResult(false, null, error);
        }
    }
}
