package com.ws.wsAgenticSecurityGateway.sts.service;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Derives the least-privilege per-hop scope embedded in a minted OBO token: the token can do
 * exactly this one capability on this one server, nothing more (JIT least privilege).
 *
 * <p>Format: {@code mcp:<type>:<server>:<capability>} — e.g. {@code mcp:tool:github:github_get_me}.
 */
@Component
public class ScopeDeriver {

    public String derive(String serverName, String publicName, String capabilityType) {
        String type = (capabilityType != null && !capabilityType.isBlank())
                ? capabilityType.toLowerCase(Locale.ROOT) : "capability";
        return "mcp:" + type + ":" + safe(serverName) + ":" + safe(publicName);
    }

    private String safe(String s) {
        return (s == null || s.isBlank()) ? "unknown" : s;
    }
}
