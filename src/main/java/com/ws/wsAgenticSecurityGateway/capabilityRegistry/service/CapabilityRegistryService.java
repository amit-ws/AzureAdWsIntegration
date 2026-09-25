package com.ws.wsAgenticSecurityGateway.capabilityRegistry.service;

import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor.CapabilityType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The protocol-neutral capability index the spine queries — a fast in-memory map from public capability name
 * to {@link CapabilityDescriptor}, plus a per-server grouping. It has no notion of any wire protocol: each
 * protocol's boundary registrar (MCP: {@code McpCapabilityRegistrar}; A2A later: its own) persists its own
 * tables and feeds descriptors in via {@link #registerServerCapabilities} / {@link #evictServer}.
 *
 * <p>Registration has replace semantics per server: registering a server's capabilities atomically evicts
 * any previous entries for that server before installing the new set.
 */
@Service
@Slf4j
public class CapabilityRegistryService {

    private final ConcurrentHashMap<String, CapabilityDescriptor> primaryIndex =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Set<String>> serverIndex =
            new ConcurrentHashMap<>();

    // ── Registration (called by each protocol's boundary registrar) ──────────────────────────────────

    /**
     * Install the full capability set for a server, replacing any previously registered entries for it.
     * The descriptors are protocol-neutral — the registrar has already mapped its wire types to them.
     */
    public void registerServerCapabilities(String serverConfigName, List<CapabilityDescriptor> descriptors) {
        evictServer(serverConfigName);

        Set<String> publicNames = ConcurrentHashMap.newKeySet();
        for (CapabilityDescriptor descriptor : descriptors) {
            primaryIndex.put(descriptor.getPublicName(), descriptor);
            publicNames.add(descriptor.getPublicName());
        }
        serverIndex.put(serverConfigName, publicNames);
        log.debug("Registry: server '{}' now exposes {} capabilities", serverConfigName, publicNames.size());
    }

    /** Remove all index entries for a server. */
    public void evictServer(String serverConfigName) {
        Set<String> publicNames = serverIndex.remove(serverConfigName);
        if (publicNames != null) {
            for (String publicName : publicNames) {
                primaryIndex.remove(publicName);
            }
        }
    }

    // ── Lookup / query (used by the spine, admin, and each protocol's inbound initializers) ───────────

    public Optional<CapabilityDescriptor> lookupByPublicName(String publicName) {
        return Optional.ofNullable(primaryIndex.get(publicName));
    }

    public Collection<CapabilityDescriptor> getAllCapabilities() {
        return Collections.unmodifiableCollection(primaryIndex.values());
    }

    public List<CapabilityDescriptor> getCapabilitiesByServer(String serverConfigName) {
        Set<String> publicNames = serverIndex.getOrDefault(serverConfigName, Set.of());
        return publicNames.stream()
                .map(primaryIndex::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * All capabilities of a given kind. The canonical type-filtered query — a new capability kind (e.g. an
     * A2A {@code SKILL}) is served by this method with no new method to add.
     */
    public List<CapabilityDescriptor> getByType(CapabilityType type) {
        return primaryIndex.values().stream()
                .filter(d -> d.getType() == type)
                .collect(Collectors.toList());
    }

    public List<CapabilityDescriptor> getToolDescriptors() {
        return getByType(CapabilityType.TOOL);
    }

    public List<CapabilityDescriptor> getResourceDescriptors() {
        return getByType(CapabilityType.RESOURCE);
    }

    public List<CapabilityDescriptor> getPromptDescriptors() {
        return getByType(CapabilityType.PROMPT);
    }

    public Set<String> getRegisteredServerNames() {
        return Collections.unmodifiableSet(serverIndex.keySet());
    }

    public int getTotalCapabilityCount() {
        return primaryIndex.size();
    }

    public boolean exists(String publicName) {
        return primaryIndex.containsKey(publicName);
    }
}
