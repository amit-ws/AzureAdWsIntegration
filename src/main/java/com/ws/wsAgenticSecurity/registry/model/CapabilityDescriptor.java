package com.ws.wsAgenticSecurity.registry.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

/**
 * Immutable in-memory descriptor for a registered capability (tool, resource, or prompt).
 *
 * <p>Held in the primary {@code ConcurrentHashMap<publicName, CapabilityDescriptor>}
 * for O(1) hot-path lookups. All fields are final once built.
 *
 * <p><strong>Naming Convention:</strong>
 * <ul>
 *   <li>{@code publicName} — namespaced identifier exposed to AI agents,
 *       e.g. {@code github.create_issue}</li>
 *   <li>{@code originalName} — the name as reported by the enterprise MCP server,
 *       e.g. {@code create_issue}</li>
 * </ul>
 */
@Getter
@Builder
@ToString
public class CapabilityDescriptor {

    /** Namespaced public name: {@code <serverConfigName>.<originalName>}. */
    private final String publicName;

    /** Original name as reported by the enterprise MCP server. */
    private final String originalName;

    /** Config-level name of the owning enterprise server (e.g. "github"). */
    private final String serverConfigName;

    /** Capability type: TOOL, RESOURCE, or PROMPT. */
    private final CapabilityType type;

    /** Human-readable description. */
    private final String description;

    /** JSON Schema string for tool input parameters (tools only). */
    private final String inputSchema;

    /** Resource URI (resources only). */
    private final String resourceUri;

    /** Resource MIME type (resources only). */
    private final String mimeType;

    /** Prompt arguments as JSON string (prompts only). */
    private final String arguments;

    /** Database ID of the owning server entity. */
    private final UUID serverId;

    /**
     * Type of MCP capability.
     */
    public enum CapabilityType {
        TOOL,
        RESOURCE,
        PROMPT
    }
}
