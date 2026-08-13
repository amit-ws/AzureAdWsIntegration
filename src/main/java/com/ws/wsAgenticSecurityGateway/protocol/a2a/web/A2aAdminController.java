package com.ws.wsAgenticSecurityGateway.protocol.a2a.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor.CapabilityType;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.A2aAgentIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin API for the downstream A2A agents the gateway fronts: ingest an agent by URL (fetch its Agent Card,
 * register its skills), list agents, read one agent's detail (card + skills) or health, list all registered
 * skills, and remove an agent. Mirrors the MCP server-config admin API's shape and, like it, sits behind the
 * permissive admin plane (tenant via the {@code X-WS-Tenant} header).
 */
@RestController
@RequestMapping("/api/admin/a2a")
@Slf4j
public class A2aAdminController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final A2aAgentIngestionService ingestionService;
    private final CapabilityRegistryService registryService;
    private final AgentRegistryService agentRegistryService;

    public A2aAdminController(A2aAgentIngestionService ingestionService,
                             CapabilityRegistryService registryService,
                             AgentRegistryService agentRegistryService) {
        this.ingestionService = ingestionService;
        this.registryService = registryService;
        this.agentRegistryService = agentRegistryService;
    }

    /** Ingest (or refresh) a downstream agent. Body: {@code {name, baseUrl}}. */
    @PostMapping("/agents")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        String baseUrl = str(body.get("baseUrl"));
        if (name == null || baseUrl == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "name and baseUrl are required"));
        }
        log.info("POST /api/admin/a2a/agents (name={}, baseUrl={})", name, baseUrl);
        try {
            A2aAgentIngestionService.IngestResult result = ingestionService.ingest(name, baseUrl);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ingested", true);
            out.put("agentName", result.agentName());
            out.put("cardName", result.cardName());
            out.put("skillsRegistered", result.skillsRegistered());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("A2A ingest failed for '{}' at {}: {}", name, baseUrl, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Register one or many downstream agents from Agent Card JSON supplied directly (no fetch) — the body is either
     * a single Agent Card object or an array of them, the shape agents publish at {@code /.well-known/agent-card.json}.
     * Each card's own {@code url} is its endpoint. Returns a per-card result so a partial bulk failure is legible.
     */
    @PostMapping("/agents/import")
    public ResponseEntity<Map<String, Object>> importCards(@RequestBody String body) {
        List<JsonNode> nodes = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            if (root.isArray()) root.forEach(nodes::add);
            else if (!root.isMissingNode() && !root.isNull()) nodes.add(root);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Body is not valid JSON."));
        }
        log.info("POST /api/admin/a2a/agents/import ({} card(s))", nodes.size());

        List<Map<String, Object>> results = new ArrayList<>();
        int registered = 0;
        for (JsonNode node : nodes) {
            Map<String, Object> r = new LinkedHashMap<>();
            try {
                if (node == null || !node.isObject()) {
                    throw new IllegalArgumentException("each entry must be an Agent Card object");
                }
                ObjectNode c = (ObjectNode) node;
                String name = c.path("name").asText("").trim();
                String url = c.path("url").asText("").trim();
                if (name.isEmpty() || url.isEmpty()) {
                    throw new IllegalArgumentException("an Agent Card must have a 'name' and a 'url'");
                }
                fillSpecDefaults(c);   // supply the A2A-spec fields the SDK requires, so a minimal card is accepted
                AgentCard card = JsonUtil.fromJson(c.toString(), AgentCard.class);
                A2aAgentIngestionService.IngestResult res = ingestionService.ingestFromCard(name, url, card);
                r.put("agent", res.agentName());
                r.put("cardName", res.cardName());
                r.put("skillsRegistered", res.skillsRegistered());
                r.put("registered", true);
                registered++;
            } catch (IllegalArgumentException e) {
                r.put("registered", false);
                r.put("error", e.getMessage());
            } catch (Exception e) {
                log.warn("A2A import: a card was rejected — {}", e.getMessage());
                r.put("registered", false);
                r.put("error", "could not register this card (ensure name, url, and each skill's id are present)");
            }
            results.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", nodes.size());
        out.put("registered", registered);
        out.put("results", results);
        return ResponseEntity.ok(out);
    }

    /**
     * Supply the A2A-spec fields the SDK's {@code AgentCard} requires but a hand-written minimal card may omit
     * (capabilities, default I/O modes, version, transport, and {@code supportedInterfaces}) — so an admin can paste
     * just {@code name}, {@code url}, and {@code skills}. Anything already present is left untouched.
     */
    private static void fillSpecDefaults(ObjectNode c) {
        if (c.path("preferredTransport").asText("").isBlank()) c.put("preferredTransport", "JSONRPC");
        if (c.path("version").asText("").isBlank()) c.put("version", "1.0.0");
        if (!c.path("capabilities").isObject()) {
            c.set("capabilities", c.objectNode()
                    .put("streaming", false).put("pushNotifications", false).put("extendedAgentCard", false));
        }
        if (!c.path("defaultInputModes").isArray() || c.path("defaultInputModes").isEmpty()) {
            c.set("defaultInputModes", c.arrayNode().add("text/plain"));
        }
        if (!c.path("defaultOutputModes").isArray() || c.path("defaultOutputModes").isEmpty()) {
            c.set("defaultOutputModes", c.arrayNode().add("text/plain"));
        }
        if (!c.path("skills").isArray()) c.set("skills", c.arrayNode());
        if (!c.path("supportedInterfaces").isArray() || c.path("supportedInterfaces").isEmpty()) {
            c.set("supportedInterfaces", c.arrayNode().add(c.objectNode()
                    .put("protocolBinding", c.path("preferredTransport").asText("JSONRPC"))
                    .put("url", c.path("url").asText(""))
                    .put("protocolVersion", "1.0")));
        }
    }

    /** The registered downstream agents for the tenant, each with its skill count. */
    @GetMapping("/agents")
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> out = agentRegistryService.getA2aAgents().stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", a.getAgentName());
                    m.put("baseUrl", a.getA2aBaseUrl());
                    m.put("createdAt", a.getFirstSeenAt());
                    m.put("skillCount", skillsOf(a.getAgentName()).size());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(out);
    }

    /** One agent's detail: its persisted config, live card name/reachability, and its registered skills. */
    @GetMapping("/agents/{name}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String name) {
        log.info("GET /api/admin/a2a/agents/{}", name);
        Optional<GatewayAgentEntity> agent = agentRegistryService.getA2aAgent(name);
        if (agent.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        GatewayAgentEntity a = agent.get();
        Optional<AgentCard> card = ingestionService.tryFetchCard(a.getA2aBaseUrl());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", a.getAgentName());
        out.put("baseUrl", a.getA2aBaseUrl());
        out.put("createdAt", a.getFirstSeenAt());
        out.put("tenant", a.getWsTenantName());
        out.put("cardName", card.map(AgentCard::name).orElse(null));
        out.put("reachable", card.isPresent());
        out.put("skills", skillsOf(name).stream().map(A2aAdminController::skillView).toList());
        return ResponseEntity.ok(out);
    }

    /** Reachability check: can the agent's card be fetched right now? */
    @GetMapping("/agents/{name}/health")
    public ResponseEntity<Map<String, Object>> health(@PathVariable String name) {
        Optional<GatewayAgentEntity> agent = agentRegistryService.getA2aAgent(name);
        if (agent.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<AgentCard> card = ingestionService.tryFetchCard(agent.get().getA2aBaseUrl());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reachable", card.isPresent());
        out.put("cardName", card.map(AgentCard::name).orElse(null));
        return ResponseEntity.ok(out);
    }

    /** All registered SKILL capabilities for the tenant's agents. */
    @GetMapping("/skills")
    public ResponseEntity<List<Map<String, Object>>> skills() {
        Set<String> tenantAgents = agentRegistryService.getA2aAgents().stream()
                .map(GatewayAgentEntity::getAgentName)
                .collect(Collectors.toSet());
        List<Map<String, Object>> out = registryService.getByType(CapabilityType.SKILL).stream()
                .filter(d -> tenantAgents.contains(d.getServerConfigName()))
                .map(A2aAdminController::skillView)
                .toList();
        return ResponseEntity.ok(out);
    }

    /** Remove a downstream agent (drops its skills, endpoint, and persisted config). */
    @DeleteMapping("/agents/{name}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String name) {
        log.info("DELETE /api/admin/a2a/agents/{}", name);
        ingestionService.remove(name);
        return ResponseEntity.ok(Map.of("removed", true, "agentName", name));
    }

    /** The SKILL capabilities registered for one agent (serverConfigName == agent name). */
    private List<CapabilityDescriptor> skillsOf(String agentName) {
        return registryService.getCapabilitiesByServer(agentName).stream()
                .filter(d -> d.getType() == CapabilityType.SKILL)
                .toList();
    }

    /** A skill capability as a dashboard-friendly map. */
    private static Map<String, Object> skillView(CapabilityDescriptor d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("publicName", d.getPublicName());      // "<agent>.<skill>"
        m.put("skillId", d.getOriginalName());        // the raw skill id
        m.put("agent", d.getServerConfigName());
        m.put("description", d.getDescription());
        m.put("protocol", d.getProtocol());           // "A2A"
        return m;
    }

    private static String str(Object value) {
        return value != null && !String.valueOf(value).isBlank() ? String.valueOf(value) : null;
    }
}
