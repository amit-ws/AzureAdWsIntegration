package com.ws.wsAgenticSecurityGateway.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceReport;
import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceReport.ControlEvidence;
import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceReport.EvidenceItem;
import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceTemplate;
import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceTemplate.TemplateEvidence;
import com.ws.wsAgenticSecurityGateway.compliance.repository.ComplianceAuditStatsRepository;
import com.ws.wsAgenticSecurityGateway.compliance.repository.ComplianceClassificationRepository;
import com.ws.wsAgenticSecurityGateway.compliance.repository.ComplianceDecisionRepository;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assembles compliance evidence packs. The <b>structure</b> is data (a shipped default template, or one an admin
 * uploads as JSON/YAML); the backend supplies a fixed <b>menu of metrics</b> computed live from the tenant's real
 * audit / PDP / identity / policy data, and fills the template's slots from that menu. A template may only reference
 * metrics that exist in the menu (validated on upload). Read-only — this is evidence for the referenced controls,
 * not a certification.
 *
 * <p>Self-contained in the {@code compliance} package: it reads its metrics through compliance-owned repositories
 * (native aggregates over the foundational {@code pdp_audit_log} / {@code gateway_audit_log} tables) plus the
 * already-live {@link GatewayAgentRepository} and {@link PolicyService}, so the module ships independently of the
 * CISO dashboard.
 */
// Explicit bean name so this standalone module can coexist with the legacy ciso.service.ComplianceService in the
// OG build without a bean-name clash (both default to "complianceService"). Injection is by type, so this is
// only about the bean id; in a compliance-only deployment the name is harmless.
@Service("complianceModuleService")
@Slf4j
public class ComplianceService {

    private static final String DEFAULT_DISCLAIMER =
            "Evidence for the listed controls, generated from the gateway's audit trail for the period shown. "
            + "This is supporting evidence — not a certification, attestation, or opinion.";

    /** Frameworks with a shipped default template (classpath {@code compliance/<id>-default.json}). */
    private static final List<String> FRAMEWORK_IDS = List.of("soc2", "sox");

    private final ComplianceDecisionRepository decisionRepo;
    private final ComplianceAuditStatsRepository auditStatsRepo;
    private final ComplianceClassificationRepository classificationRepo;
    private final GatewayAgentRepository agentRepo;
    private final PolicyService policyService;

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ComplianceService(ComplianceDecisionRepository decisionRepo,
                             ComplianceAuditStatsRepository auditStatsRepo,
                             ComplianceClassificationRepository classificationRepo,
                             GatewayAgentRepository agentRepo, PolicyService policyService) {
        this.decisionRepo = decisionRepo;
        this.auditStatsRepo = auditStatsRepo;
        this.classificationRepo = classificationRepo;
        this.agentRepo = agentRepo;
        this.policyService = policyService;
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /** The default (shipped) SOC 2 report, filled from live data. Kept for the simple {@code /compliance/soc2} route. */
    public ComplianceReport soc2Report() {
        return render("soc2", null);
    }

    /** Frameworks that have a shipped default template — {@code [{id, name}]}, powering the FE framework switcher.
     *  The display name is read from each template so it never drifts from the report title. */
    public List<Map<String, String>> frameworks() {
        List<Map<String, String>> out = new ArrayList<>();
        for (String id : FRAMEWORK_IDS) {
            try {
                out.add(Map.of("id", id, "name", defaultTemplate(id).framework()));
            } catch (Exception e) {
                log.warn("Compliance framework '{}' has no readable default template: {}", id, e.getMessage());
            }
        }
        return out;
    }

    /** Render {@code template} (or the shipped default for {@code framework} when null) against live tenant data. */
    public ComplianceReport render(String framework, ComplianceTemplate template) {
        String tenant = TenantContext.get();
        ComplianceData data = gather(tenant);
        ComplianceTemplate t = (template != null) ? template : defaultTemplate(framework);
        return build(t, data, tenant);
    }

    /** The live metric menu (key → current value) — what a template may reference. Powers the authoring UI. */
    public Map<String, String> metricMenu() {
        return gather(TenantContext.get()).metrics();
    }

    /** Parse an uploaded template — JSON first, then YAML. Throws {@link IllegalArgumentException} on both failing. */
    public ComplianceTemplate parseTemplate(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Template content is empty.");
        }
        try {
            return json.readValue(content, ComplianceTemplate.class);
        } catch (Exception jsonEx) {
            try {
                return yaml.readValue(content, ComplianceTemplate.class);
            } catch (Exception yamlEx) {
                throw new IllegalArgumentException("Template is neither valid JSON nor YAML: " + yamlEx.getMessage());
            }
        }
    }

    /** Metric keys the template references that the gateway does NOT provide (empty ⇒ the template is fillable). */
    public List<String> unknownMetrics(ComplianceTemplate t) {
        Map<String, String> menu = gather(TenantContext.get()).metrics();
        if (t == null || t.controls() == null) return List.of();
        return t.controls().stream()
                .filter(c -> c != null && c.evidence() != null)
                .flatMap(c -> c.evidence().stream())
                .map(TemplateEvidence::metric)
                .filter(Objects::nonNull)
                .filter(m -> !menu.containsKey(m))
                .distinct()
                .toList();
    }

    /** Flatten a report to CSV (one row per evidence line) for auditor/spreadsheet export. */
    public String toCsv(ComplianceReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("framework,tenant,generated_at,period_start,period_end\n");
        sb.append(row(r.framework(), r.tenant(), str(r.generatedAt()), str(r.periodStart()), str(r.periodEnd())));
        sb.append('\n');
        sb.append("control_ref,control_title,status,evidence_label,evidence_value\n");
        for (ControlEvidence c : r.controls()) {
            for (EvidenceItem e : c.evidence()) {
                sb.append(row(c.ref(), c.title(), c.status(), e.label(), e.value()));
            }
        }
        return sb.toString();
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private ComplianceReport build(ComplianceTemplate t, ComplianceData data, String tenant) {
        Map<String, String> menu = data.metrics();
        List<ControlEvidence> controls = t.controls().stream().map(tc -> {
            List<EvidenceItem> ev = tc.evidence().stream()
                    .map(te -> new EvidenceItem(te.label(),
                            menu.containsKey(te.metric()) ? menu.get(te.metric())
                                    : "(unknown metric: " + te.metric() + ")"))
                    .toList();
            boolean anyKnown = tc.evidence().stream().anyMatch(te -> menu.containsKey(te.metric()));
            return new ControlEvidence(tc.ref(), tc.title(), tc.requirement(), tc.howSatisfied(),
                    anyKnown ? "EVIDENCED" : "NO_DATA", ev);
        }).toList();
        String disclaimer = (t.disclaimer() != null && !t.disclaimer().isBlank()) ? t.disclaimer() : DEFAULT_DISCLAIMER;
        return new ComplianceReport(t.framework(), tenant, LocalDateTime.now(),
                data.periodStart(), data.periodEnd(), disclaimer, controls);
    }

    private ComplianceTemplate defaultTemplate(String framework) {
        String slug = (framework == null ? "" : framework.toLowerCase().replaceAll("[^a-z0-9]+", ""));
        String path = "compliance/" + slug + "-default.json";
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return json.readValue(in, ComplianceTemplate.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("No default template for framework '" + framework
                    + "'. Upload a template, or use one of: " + String.join(", ", FRAMEWORK_IDS) + ".");
        }
    }

    /** The live metric menu + reporting period, gathered once per report. */
    private ComplianceData gather(String tenant) {
        long[] pdp = firstRow(decisionRepo.policyDecisionCoverage(tenant), 5);
        long dTotal = pdp[0], dAllow = pdp[1], dDeny = pdp[2], dAttr = pdp[3], dUnattr = pdp[4];

        AuditStats a = auditStats(tenant);

        List<GatewayAgentEntity> agents = agentRepo.findAllByWsTenantName(tenant);
        long agTotal = agents.size();
        long agApproved = agents.stream().filter(g -> "APPROVED".equalsIgnoreCase(g.getApprovalStatus())).count();
        long agPending = agents.stream().filter(g -> "PENDING".equalsIgnoreCase(g.getApprovalStatus())).count();

        DataClassStats dc = dataClassStats(tenant);
        long dcCategories = orZero(classificationRepo.distinctCategoryCount(tenant));
        long dcRules = orZero(classificationRepo.activeCustomRuleCount(tenant));

        List<GatewayPolicyEntity> policies = policyService.getAllPolicies();
        long pTotal = policies.size();
        long pEnabled = policies.stream().filter(p -> Boolean.TRUE.equals(p.getEnabled())).count();
        long pManual = policies.stream().filter(p -> "MANUAL".equalsIgnoreCase(p.getSource())).count();
        long pGen = policies.stream().filter(p -> "LLM_GENERATED".equalsIgnoreCase(p.getSource())).count();
        long pDef = policies.stream().filter(p -> "DEFAULT".equalsIgnoreCase(p.getSource())).count();
        LocalDateTime lastChange = policies.stream().map(GatewayPolicyEntity::getUpdatedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);

        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("decisions.total", num(dTotal));
        m.put("decisions.allowed", num(dAllow));
        m.put("decisions.denied", num(dDeny));
        m.put("decisions.attributed", num(dAttr));
        m.put("decisions.unattributed", num(dUnattr));
        m.put("decisions.attributedOfTotal", num(dAttr) + " of " + num(dTotal));
        m.put("audit.events", num(a.total));
        m.put("audit.eventTypes", num(a.types));
        m.put("audit.humanAttributed", num(a.humanAttr));
        m.put("audit.humanAttributedOfTotal", num(a.humanAttr) + " of " + num(a.total));
        m.put("audit.distinctHumans", num(a.humans));
        m.put("audit.distinctAgents", num(a.agents));
        m.put("audit.periodStart", a.first == null ? "n/a" : a.first.toLocalDate().toString());
        m.put("audit.periodEnd", a.last == null ? "n/a" : a.last.toLocalDate().toString());
        m.put("audit.period", period(a.first, a.last));
        m.put("agents.total", num(agTotal));
        m.put("agents.approved", num(agApproved));
        m.put("agents.pending", num(agPending));
        m.put("policies.total", num(pTotal));
        m.put("policies.enabled", num(pEnabled));
        m.put("policies.manual", num(pManual));
        m.put("policies.generated", num(pGen));
        m.put("policies.defaults", num(pDef));
        m.put("policies.lastChange", lastChange == null ? "n/a" : lastChange.toLocalDate().toString());
        // Egress data-classification (post-processor) — data-integrity / confidentiality evidence, financial-aware.
        m.put("dataclass.classifications", num(dc.total));
        m.put("dataclass.sensitive", num(dc.sensitive));
        m.put("dataclass.sensitiveOfTotal", num(dc.sensitive) + " of " + num(dc.total));
        m.put("dataclass.restricted", num(dc.restricted));
        m.put("dataclass.confidential", num(dc.confidential));
        m.put("dataclass.financial", num(dc.financial));
        m.put("dataclass.injections", num(dc.injections));
        m.put("dataclass.humanAttributed", num(dc.humanAttr));
        m.put("dataclass.humanAttributedOfTotal", num(dc.humanAttr) + " of " + num(dc.total));
        m.put("dataclass.categories", num(dcCategories));
        m.put("dataclass.rulesActive", num(dcRules));
        m.put("dataclass.periodStart", dc.first == null ? "n/a" : dc.first.toLocalDate().toString());
        m.put("dataclass.periodEnd", dc.last == null ? "n/a" : dc.last.toLocalDate().toString());
        m.put("dataclass.period", period(dc.first, dc.last));

        LocalDate ps = a.first == null ? null : a.first.toLocalDate();
        LocalDate pe = a.last == null ? null : a.last.toLocalDate();
        return new ComplianceData(m, ps, pe);
    }

    private AuditStats auditStats(String tenant) {
        List<Object[]> rows = auditStatsRepo.tenantAuditStats(tenant);
        if (rows == null || rows.isEmpty()) {
            return new AuditStats(0, 0, 0, 0, 0, null, null);
        }
        Object[] r = rows.get(0);
        return new AuditStats(asLong(r[0]), asLong(r[1]), asLong(r[2]), asLong(r[3]), asLong(r[4]),
                asDateTime(r[5]), asDateTime(r[6]));
    }

    private DataClassStats dataClassStats(String tenant) {
        List<Object[]> rows = classificationRepo.dataClassStats(tenant);
        if (rows == null || rows.isEmpty()) {
            return new DataClassStats(0, 0, 0, 0, 0, 0, 0, null, null);
        }
        Object[] r = rows.get(0);
        return new DataClassStats(asLong(r[0]), asLong(r[1]), asLong(r[2]), asLong(r[3]), asLong(r[4]),
                asLong(r[5]), asLong(r[6]), asDateTime(r[7]), asDateTime(r[8]));
    }

    private record ComplianceData(Map<String, String> metrics, LocalDate periodStart, LocalDate periodEnd) {}

    private record AuditStats(long total, long types, long humanAttr, long humans, long agents,
                              LocalDateTime first, LocalDateTime last) {}

    private record DataClassStats(long total, long sensitive, long restricted, long confidential,
                                  long financial, long injections, long humanAttr,
                                  LocalDateTime first, LocalDateTime last) {}

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }

    private static String num(long n) {
        return Long.toString(n);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String period(LocalDateTime first, LocalDateTime last) {
        if (first == null || last == null) return "n/a";
        LocalDate f = first.toLocalDate(), l = last.toLocalDate();
        return f.equals(l) ? f.toString() : f + " to " + l;
    }

    private static String row(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csv(cells[i]));
        }
        return sb.append('\n').toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static long[] firstRow(List<Object[]> rows, int n) {
        long[] out = new long[n];
        if (rows == null || rows.isEmpty()) return out;
        Object[] r = rows.get(0);
        for (int i = 0; i < n && i < r.length; i++) out[i] = asLong(r[i]);
        return out;
    }

    private static long asLong(Object o) {
        return (o instanceof Number x) ? x.longValue() : 0L;
    }

    private static LocalDateTime asDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime ldt) return ldt;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }
}
