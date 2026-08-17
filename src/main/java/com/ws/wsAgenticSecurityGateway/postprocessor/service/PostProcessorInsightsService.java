package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.Sensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.CapabilityFingerprint;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DriftSignal;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.EntitySensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.InsightsReport;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.SharingEdge;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.SkillSensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ToolSensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The egress Insights layer (Step 4): fingerprints, the sensitive-data-sharing map, and drift — all derived live
 * from the classification table via SQL aggregates (grouped counts), pivoted here. Read-only; nothing stored,
 * nothing fabricated. Tenant-scoped via {@link TenantContext}.
 */
@Service
public class PostProcessorInsightsService {

    /** Recent window for drift — a capability escalating within this window vs its earlier baseline is flagged. */
    private static final long DRIFT_WINDOW_HOURS = 24;

    private final GatewayResponseClassificationRepository repository;

    public PostProcessorInsightsService(GatewayResponseClassificationRepository repository) {
        this.repository = repository;
    }

    public InsightsReport insights() {
        String tenant = TenantContext.get();
        return new InsightsReport(fingerprints(tenant), sharingEdges(tenant), drift(tenant), LocalDateTime.now());
    }

    /** Per-entity peak sensitivity for badging the Agents / Servers pages — keyed by exact agent/server ids. */
    public EntitySensitivity entitySensitivity() {
        String tenant = TenantContext.get();

        Map<String, Integer> agents = new HashMap<>();
        mergeRank(agents, repository.consumerAgentSensitivity(tenant));
        mergeRank(agents, repository.producerAgentSensitivity(tenant));

        Map<String, Integer> servers = new HashMap<>();
        Map<String, Integer> serversByName = new HashMap<>();
        for (Object[] r : repository.serverSensitivity(tenant)) {
            int rank = Sensitivity.rankOf(str(r[2]));
            if (r[0] != null) {
                servers.merge(str(r[0]), rank, Math::max);
            }
            if (r[1] != null) {
                serversByName.merge(str(r[1]), rank, Math::max);
            }
        }

        Map<String, Tool> tools = new LinkedHashMap<>();
        for (Object[] r : repository.serverCapabilitySensitivity(tenant)) {
            String serverId = str(r[0]);
            String serverName = str(r[1]);
            String type = str(r[2]);
            String name = str(r[3]);
            int rank = Sensitivity.rankOf(str(r[4]));
            tools.computeIfAbsent(serverId + "|" + type + "|" + name, k -> new Tool(serverId, serverName, type, name))
                    .bump(rank);
        }
        List<ToolSensitivity> toolList = tools.values().stream()
                .map(t -> new ToolSensitivity(t.serverId, t.serverName, t.type, t.name, Sensitivity.labelOf(t.rank)))
                .toList();

        Map<String, Skill> skills = new LinkedHashMap<>();
        for (Object[] r : repository.skillSensitivity(tenant)) {
            String agentId = str(r[0]);
            String agentName = str(r[1]);
            String name = str(r[2]);
            int rank = Sensitivity.rankOf(str(r[3]));
            skills.computeIfAbsent(agentId + "|" + name, k -> new Skill(agentId, agentName, name)).bump(rank);
        }
        List<SkillSensitivity> skillList = skills.values().stream()
                .map(s -> new SkillSensitivity(s.agentId, s.agentName, s.name, Sensitivity.labelOf(s.rank)))
                .toList();

        return new EntitySensitivity(toLabels(agents), toLabels(servers), toLabels(serversByName), toolList, skillList);
    }

    // ── Fingerprints (per producer + capability) ─────────────────────────────
    private List<CapabilityFingerprint> fingerprints(String tenant) {
        Map<String, Profile> byCapability = new LinkedHashMap<>();
        for (Object[] r : repository.capabilityProfileRows(tenant)) {
            String producer = str(r[0]);
            String capability = str(r[1]);
            String capabilityType = str(r[2]);
            String protocol = str(r[3]);
            String sensitivity = str(r[4]);
            long count = asLong(r[5]);
            LocalDateTime last = asDateTime(r[6]);
            String key = producer + "|" + capability + "|" + capabilityType + "|" + protocol;
            byCapability.computeIfAbsent(key, k -> new Profile(producer, capability, capabilityType, protocol))
                    .add(sensitivity, count, last);
        }
        return byCapability.values().stream()
                .map(Profile::toFingerprint)
                .sorted(Comparator.comparingLong(CapabilityFingerprint::sensitiveCount)
                        .thenComparingLong(CapabilityFingerprint::total).reversed())
                .toList();
    }

    // ── Sharing map (producer → consumer edges) ──────────────────────────────
    private List<SharingEdge> sharingEdges(String tenant) {
        Map<String, Profile> byEdge = new LinkedHashMap<>();
        for (Object[] r : repository.sharingEdgeRows(tenant)) {
            String producer = str(r[0]);
            String consumer = str(r[1]);
            String sensitivity = str(r[2]);
            long count = asLong(r[3]);
            LocalDateTime last = asDateTime(r[4]);
            String key = producer + "→" + consumer;
            byEdge.computeIfAbsent(key, k -> new Profile(producer, consumer, null, null))
                    .add(sensitivity, count, last);
        }
        return byEdge.values().stream()
                .map(Profile::toEdge)
                .sorted(Comparator.comparingLong(SharingEdge::sensitiveCount)
                        .thenComparingLong(SharingEdge::total).reversed())
                .toList();
    }

    // ── Drift (recent escalation vs baseline) ────────────────────────────────
    private List<DriftSignal> drift(String tenant) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(DRIFT_WINDOW_HOURS);
        Map<String, DriftAcc> byCapability = new LinkedHashMap<>();
        for (Object[] r : repository.driftRows(tenant, cutoff)) {
            String producer = str(r[0]);
            String capability = str(r[1]);
            int rank = Sensitivity.rankOf(str(r[2]));
            boolean recent = asBool(r[3]);
            long count = asLong(r[4]);
            DriftAcc acc = byCapability.computeIfAbsent(producer + "|" + capability,
                    k -> new DriftAcc(producer, capability));
            if (recent) {
                acc.addRecent(rank, count);
            } else {
                acc.addBaseline(rank);
            }
        }
        List<DriftSignal> signals = new ArrayList<>();
        for (DriftAcc a : byCapability.values()) {
            if (!a.baselineExists) {
                continue; // no prior history → not "drift", just first-seen
            }
            int recentPeak = a.recentPeak();
            if (recentPeak > a.baselinePeak) {
                signals.add(new DriftSignal(a.producer, a.capability,
                        Sensitivity.labelOf(a.baselinePeak), Sensitivity.labelOf(recentPeak), a.recentCountAtPeak()));
            }
        }
        signals.sort(Comparator.comparingInt((DriftSignal d) -> Sensitivity.rankOf(d.recentPeak())).reversed());
        return signals;
    }

    // ── Accumulators + helpers ───────────────────────────────────────────────

    private static final class Profile {
        private final String a;
        private final String b;
        private final String type;
        private final String protocol;
        private final Map<String, Long> bySensitivity = new LinkedHashMap<>();
        private long total;
        private LocalDateTime lastSeen;

        Profile(String a, String b, String type, String protocol) {
            this.a = a;
            this.b = b;
            this.type = type;
            this.protocol = protocol;
        }

        void add(String sensitivity, long count, LocalDateTime last) {
            String s = sensitivity == null ? "PUBLIC" : sensitivity;
            bySensitivity.merge(s, count, Long::sum);
            total += count;
            if (last != null && (lastSeen == null || last.isAfter(lastSeen))) {
                lastSeen = last;
            }
        }

        String peak() {
            String best = "PUBLIC";
            int bestRank = 0;
            for (String s : bySensitivity.keySet()) {
                int r = Sensitivity.rankOf(s);
                if (r >= bestRank) {
                    bestRank = r;
                    best = s;
                }
            }
            return best;
        }

        long sensitiveCount() {
            long n = 0;
            for (Map.Entry<String, Long> e : bySensitivity.entrySet()) {
                if (Sensitivity.rankOf(e.getKey()) > 0) {
                    n += e.getValue();
                }
            }
            return n;
        }

        CapabilityFingerprint toFingerprint() {
            return new CapabilityFingerprint(a, b, type, protocol, total, sensitiveCount(), peak(),
                    new LinkedHashMap<>(bySensitivity), lastSeen);
        }

        SharingEdge toEdge() {
            return new SharingEdge(a, b, total, sensitiveCount(), peak(), new LinkedHashMap<>(bySensitivity), lastSeen);
        }
    }

    private static final class DriftAcc {
        private final String producer;
        private final String capability;
        private int baselinePeak;
        private boolean baselineExists;
        private final Map<Integer, Long> recentByRank = new HashMap<>();

        DriftAcc(String producer, String capability) {
            this.producer = producer;
            this.capability = capability;
        }

        void addBaseline(int rank) {
            baselinePeak = Math.max(baselinePeak, rank);
            baselineExists = true;
        }

        void addRecent(int rank, long count) {
            recentByRank.merge(rank, count, Long::sum);
        }

        int recentPeak() {
            return recentByRank.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        long recentCountAtPeak() {
            return recentByRank.getOrDefault(recentPeak(), 0L);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean asBool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        return o instanceof Number n && n.intValue() != 0;
    }

    private static LocalDateTime asDateTime(Object o) {
        if (o instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    /** Merge grouped [id, sensitivity, count] rows into a running max rank per id. */
    private static void mergeRank(Map<String, Integer> into, List<Object[]> rows) {
        for (Object[] r : rows) {
            if (r[0] == null) {
                continue;
            }
            into.merge(str(r[0]), Sensitivity.rankOf(str(r[1])), Math::max);
        }
    }

    private static Map<String, String> toLabels(Map<String, Integer> ranks) {
        Map<String, String> out = new LinkedHashMap<>();
        ranks.forEach((k, v) -> out.put(k, Sensitivity.labelOf(v)));
        return out;
    }

    private static final class Tool {
        private final String serverId;
        private final String serverName;
        private final String type;
        private final String name;
        private int rank;

        Tool(String serverId, String serverName, String type, String name) {
            this.serverId = serverId;
            this.serverName = serverName;
            this.type = type;
            this.name = name;
        }

        void bump(int r) {
            rank = Math.max(rank, r);
        }
    }

    private static final class Skill {
        private final String agentId;
        private final String agentName;
        private final String name;
        private int rank;

        Skill(String agentId, String agentName, String name) {
            this.agentId = agentId;
            this.agentName = agentName;
            this.name = name;
        }

        void bump(int r) {
            rank = Math.max(rank, r);
        }
    }
}
