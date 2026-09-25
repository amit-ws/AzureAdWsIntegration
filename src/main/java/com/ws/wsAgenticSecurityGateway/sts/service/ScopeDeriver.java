package com.ws.wsAgenticSecurityGateway.sts.service;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Derives the least-privilege per-hop scope embedded in a minted OBO token: the token can do
 * exactly this one capability on this one server, nothing more (JIT least privilege).
 *
 * <p>Format: {@code <protocol>:<type>:<server>:<capability>} — e.g. {@code mcp:tool:github:github_get_me}
 * for MCP, {@code a2a:skill:planner:decompose} for A2A. The protocol prefix is supplied by the adapter, so
 * the scope scheme is not MCP-branded on the wire.
 */
@Component
public class ScopeDeriver {

    public String derive(String protocol, String serverName, String publicName, String capabilityType) {
        String proto = (protocol != null && !protocol.isBlank())
                ? protocol.toLowerCase(Locale.ROOT) : "mcp";
        String type = (capabilityType != null && !capabilityType.isBlank())
                ? capabilityType.toLowerCase(Locale.ROOT) : "capability";
        return proto + ":" + type + ":" + safe(serverName) + ":" + safe(publicName);
    }

    private String safe(String s) {
        return (s == null || s.isBlank()) ? "unknown" : s;
    }
}
