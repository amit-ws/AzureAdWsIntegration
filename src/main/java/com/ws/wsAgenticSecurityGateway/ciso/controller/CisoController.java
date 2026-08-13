package com.ws.wsAgenticSecurityGateway.ciso.controller;

import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentAccountability;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentActivityTrail;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius;
import com.ws.wsAgenticSecurityGateway.ciso.dto.ComplianceReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.ComplianceTemplate;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PointInTimeEvents;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PointInTimeSnapshot;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PostureReport;
import com.ws.wsAgenticSecurityGateway.ciso.service.AccountabilityService;
import com.ws.wsAgenticSecurityGateway.ciso.service.AgentActivityTrailService;
import com.ws.wsAgenticSecurityGateway.ciso.service.BlastRadiusService;
import com.ws.wsAgenticSecurityGateway.ciso.service.ComplianceService;
import com.ws.wsAgenticSecurityGateway.ciso.service.PointInTimeService;
import com.ws.wsAgenticSecurityGateway.ciso.service.PostureService;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CISO dashboard endpoints — the cross-cutting, executive-facing views (blast radius today; posture, exposure,
 * and compliance to follow). Per-object detail lives in its own section (policies, agents); this surface hosts
 * the roll-ups and attack-surface views. All read-only and tenant-scoped.
 */
@RestController
@RequestMapping("/api/admin/ciso")
@Slf4j
public class CisoController {

    private final BlastRadiusService blastRadiusService;
    private final ComplianceService complianceService;
    private final AgentActivityTrailService activityTrailService;
    private final AccountabilityService accountabilityService;
    private final PostureService postureService;
    private final PointInTimeService pointInTimeService;

    public CisoController(BlastRadiusService blastRadiusService, ComplianceService complianceService,
                          AgentActivityTrailService activityTrailService,
                          AccountabilityService accountabilityService,
                          PostureService postureService,
                          PointInTimeService pointInTimeService) {
        this.blastRadiusService = blastRadiusService;
        this.complianceService = complianceService;
        this.activityTrailService = activityTrailService;
        this.accountabilityService = accountabilityService;
        this.postureService = postureService;
        this.pointInTimeService = pointInTimeService;
    }

    /**
     * An agent's blast radius / attack surface — "what can this agent reach, and what has it actually reached?"
     * Actual reach (audit) is the reliable spine; policy grants are layered on and labeled
     * (active / latent / conditional / blocked), with group-scoped grants recovered from observed audit decisions.
     */
    @GetMapping("/blast-radius")
    public ResponseEntity<AgentBlastRadius> blastRadius(@RequestParam("agentId") String agentId) {
        log.info("GET /api/admin/ciso/blast-radius agentId={}", agentId);
        return ResponseEntity.ok(blastRadiusService.getAgentBlastRadius(agentId));
    }

    /**
     * An agent's forensic activity trail — both directions (what it did, and what was asked of it), reconstructed
     * from the decision ledger and correlation-stitched. {@code ?format=csv} for a spreadsheet export; default JSON.
     */
    @GetMapping("/activity-trail")
    public ResponseEntity<?> activityTrail(@RequestParam("agentId") String agentId,
                                           @RequestParam(value = "format", defaultValue = "json") String format) {
        log.info("GET /api/admin/ciso/activity-trail agentId={} format={}", agentId, format);
        AgentActivityTrail trail = activityTrailService.getActivityTrail(agentId);
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header("Content-Disposition", "attachment; filename=\"activity-trail-" + agentId + ".csv\"")
                    .body(activityTrailService.toCsv(trail));
        }
        return ResponseEntity.ok(trail);
    }

    /**
     * Tenant-wide human accountability — for every agent, the verified human answerable for its governed actions
     * (direct vs delegated), the reverse per-human footprint, and policy ownership. Derived from the OBO
     * delegation lineage (actChain) on real PDP evaluations; no owner is invented.
     */
    @GetMapping("/accountability")
    public ResponseEntity<AccountabilityReport> accountability() {
        log.info("GET /api/admin/ciso/accountability");
        return ResponseEntity.ok(accountabilityService.getReport());
    }

    /**
     * One agent's accountability detail — the verified human(s) answerable for it, the direct/delegated split,
     * and the distinct OBO delegation lineages that reached it.
     */
    @GetMapping("/accountability/agent")
    public ResponseEntity<AgentAccountability> agentAccountability(@RequestParam("agentId") String agentId) {
        log.info("GET /api/admin/ciso/accountability/agent agentId={}", agentId);
        return ResponseEntity.ok(accountabilityService.getAgentAccountability(agentId));
    }

    /**
     * Security-posture scorecard — how tightly governed the tenant's agent estate is right now. Default-deny-correct:
     * broad ENABLED permits are the risk, not disabled/missing ones. Returns a 0–100 score + grade + per-check
     * detail (each with its real number and a one-line "why"). Read-only.
     */
    @GetMapping("/posture")
    public ResponseEntity<PostureReport> posture() {
        log.info("GET /api/admin/ciso/posture");
        return ResponseEntity.ok(postureService.getReport());
    }

    /**
     * Point-in-time "time machine" — replays the decision ledger to reconstruct the governance state <b>observed</b>
     * over a past window: enforcement, active agents, the effective (deciding) policy set, and accountability.
     * {@code ?from=&to=} bound the window (ISO dates, both optional); {@code ?asOf=} is shorthand for "up to that
     * date". Observed, not declared — see the response notes and {@code docs/policy-versioning-design.md}.
     */
    @GetMapping("/point-in-time")
    public ResponseEntity<?> pointInTime(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate effectiveTo = to != null ? to : asOf;   // asOf = "reconstruct everything up to this date"
        log.info("GET /api/admin/ciso/point-in-time from={} to={}", from, effectiveTo);
        if (from != null && effectiveTo != null && from.isAfter(effectiveTo)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid window: 'from' (" + from + ") must be on or before 'to'/'asOf' (" + effectiveTo + ")."));
        }
        return ResponseEntity.ok(pointInTimeService.getSnapshot(from, effectiveTo));
    }

    /**
     * Forensic evidence drill-down for a past window — the individual authorization decisions behind the overview's
     * counts, newest first: {@code time · agent · human · action · resource · allow/deny · policy}. Paginated
     * ({@code page}, {@code size}) and optionally filtered by {@code agentId} and {@code decision} (ALLOW/DENY).
     * Same window params as the overview ({@code from}/{@code to}/{@code asOf}).
     */
    @GetMapping("/point-in-time/events")
    public ResponseEntity<?> pointInTimeEvents(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "decision", required = false) String decision,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        LocalDate effectiveTo = to != null ? to : asOf;
        log.info("GET /api/admin/ciso/point-in-time/events from={} to={} agentId={} decision={} page={} size={}",
                from, effectiveTo, agentId, decision, page, size);
        if (from != null && effectiveTo != null && from.isAfter(effectiveTo)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid window: 'from' (" + from + ") must be on or before 'to'/'asOf' (" + effectiveTo + ")."));
        }
        PointInTimeEvents events = pointInTimeService.getEvents(from, effectiveTo, agentId, decision, page, size);
        return ResponseEntity.ok(events);
    }

    /**
     * The live metric "menu" (key → current value) — every value a compliance template may reference. Powers
     * the template-authoring UI and lets a client see exactly what data the gateway can supply.
     */
    @GetMapping("/compliance/metrics")
    public ResponseEntity<Map<String, String>> complianceMetrics() {
        return ResponseEntity.ok(complianceService.metricMenu());
    }

    /**
     * SOC 2 evidence pack using the shipped default template, filled from live data. {@code ?format=csv} returns
     * a spreadsheet-friendly CSV; default is JSON. Supporting evidence for the listed controls, not a certification.
     */
    @GetMapping("/compliance/soc2")
    public ResponseEntity<?> soc2(@RequestParam(value = "format", defaultValue = "json") String format) {
        log.info("GET /api/admin/ciso/compliance/soc2 format={}", format);
        return respond(complianceService.soc2Report(), format);
    }

    /**
     * Render an <b>uploaded</b> template (JSON or YAML, auto-detected) against live data. The template is validated
     * first — any metric it references that the gateway does not provide is reported as a 400 rather than filled
     * with a wrong value. {@code ?format=csv} for CSV export; default JSON.
     */
    @PostMapping("/compliance/render")
    public ResponseEntity<?> renderCompliance(
            @RequestParam(value = "format", defaultValue = "json") String format,
            @RequestBody String templateText) {
        log.info("POST /api/admin/ciso/compliance/render format={}", format);
        ComplianceTemplate template = complianceService.parseTemplate(templateText);
        List<String> unknown = complianceService.unknownMetrics(template);
        if (!unknown.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Template references metrics the gateway does not provide.",
                    "unknownMetrics", unknown));
        }
        return respond(complianceService.render(template.framework(), template), format);
    }

    /** JSON by default; {@code format=csv} returns text/csv as a downloadable attachment. */
    private ResponseEntity<?> respond(ComplianceReport report, String format) {
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header("Content-Disposition", "attachment; filename=\"compliance-" + report.framework()
                            .toLowerCase().replaceAll("[^a-z0-9]+", "-") + ".csv\"")
                    .body(complianceService.toCsv(report));
        }
        return ResponseEntity.ok(report);
    }
}
