package com.ws.wsAgenticSecurityGateway.compliance.controller;

import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceReport;
import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceTemplate;
import com.ws.wsAgenticSecurityGateway.compliance.service.ComplianceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Compliance evidence API — standalone module (own base path {@code /api/admin/compliance}), independent of the
 * CISO dashboard. Generates auditor-ready evidence packs from the gateway's real audit / PDP / identity / policy
 * data: SOC 2 today via the shipped template, plus a bring-your-own-template render path. Every value is live;
 * nothing is fabricated. Read-only.
 */
@RestController
@RequestMapping("/api/admin/compliance")
@Slf4j
public class ComplianceController {

    private final ComplianceService complianceService;

    public ComplianceController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    /** The live metric "menu" (key → current value) — every value a compliance template may reference. */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, String>> metrics() {
        return ResponseEntity.ok(complianceService.metricMenu());
    }

    /**
     * SOC 2 evidence pack using the shipped default template, filled from live data. {@code ?format=csv} returns a
     * spreadsheet-friendly CSV; default is JSON. Supporting evidence for the listed controls, not a certification.
     */
    @GetMapping("/soc2")
    public ResponseEntity<?> soc2(@RequestParam(value = "format", defaultValue = "json") String format) {
        log.info("GET /api/admin/compliance/soc2 format={}", format);
        return respond(complianceService.soc2Report(), format);
    }

    /**
     * Render an <b>uploaded</b> template (JSON or YAML, auto-detected) against live data. The template is validated
     * first — any metric it references that the gateway does not provide is reported as a 400 rather than filled with
     * a wrong value. {@code ?format=csv} for CSV export; default JSON.
     */
    @PostMapping("/render")
    public ResponseEntity<?> render(
            @RequestParam(value = "format", defaultValue = "json") String format,
            @RequestBody String templateText) {
        log.info("POST /api/admin/compliance/render format={}", format);
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
