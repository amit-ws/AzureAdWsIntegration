package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.Sensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DataTagRuleDto;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DetectorInfo;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.EffectiveDetectorView;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.RuleTemplatePackView;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.RuleTemplateView;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.TemplateInstallResult;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.DataTagRuleEntity;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.DataTagRuleType;
import com.ws.wsAgenticSecurityGateway.postprocessor.model.RuleTemplate;
import com.ws.wsAgenticSecurityGateway.postprocessor.model.RuleTemplateCatalog;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.DataTagRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Admin CRUD for egress-classifier rules — tenant-scoped, validated, and cache-aware: every write invalidates the
 * {@link ClassifierRuleService} cache so the next classified response uses the new configuration. Validation is
 * strict (a bad regex or a missing target is a 400, not a silently-ignored rule).
 */
@Service
@Slf4j
public class PostProcessorRuleService {

    private final DataTagRuleRepository repository;
    private final ClassifierRuleService ruleEngine;

    public PostProcessorRuleService(DataTagRuleRepository repository, ClassifierRuleService ruleEngine) {
        this.repository = repository;
        this.ruleEngine = ruleEngine;
    }

    public List<DataTagRuleDto> list() {
        return repository.findByWsTenantNameOrderByCreatedAtDesc(TenantContext.get()).stream()
                .map(DataTagRuleDto::from)
                .toList();
    }

    /** The built-in detectors an admin can DISABLE / OVERRIDE (the rule-authoring target list). */
    public List<DetectorInfo> detectors() {
        return ruleEngine.builtinDetectors();
    }

    public DataTagRuleDto create(DataTagRuleDto in) {
        String tenant = TenantContext.get();
        DataTagRuleEntity e = DataTagRuleEntity.builder()
                .wsTenantName(tenant)
                .name(trim(in.name()))
                .ruleType(parseType(in.ruleType()))
                .builtinMatcher(trim(in.builtinMatcher()))
                .matchType(normalizeMatchType(in.matchType()))
                .pattern(in.pattern())
                .keywords(cleanList(in.keywords()))
                .dataCategories(cleanList(in.dataCategories()))
                .sensitivity(upper(in.sensitivity()))
                .contextKey(trim(in.contextKey()))
                .confidence(in.confidence())
                .enabled(in.enabled() == null || in.enabled())
                .description(in.description())
                .createdAt(LocalDateTime.now())
                .build();
        validate(e);
        requireUniqueName(tenant, e.getName(), null);
        DataTagRuleEntity saved = repository.save(e);
        ruleEngine.invalidate(tenant);
        log.info("Egress rule created: tenant={} type={} name='{}'", tenant, saved.getRuleType(), saved.getName());
        return DataTagRuleDto.from(saved);
    }

    public DataTagRuleDto update(UUID id, DataTagRuleDto in) {
        String tenant = TenantContext.get();
        DataTagRuleEntity e = find(id, tenant);
        if (in.ruleType() != null) {
            e.setRuleType(parseType(in.ruleType()));
        }
        if (in.name() != null) {
            e.setName(trim(in.name()));
        }
        e.setBuiltinMatcher(trim(in.builtinMatcher()));
        e.setMatchType(normalizeMatchType(in.matchType()));
        e.setPattern(in.pattern());
        e.setKeywords(cleanList(in.keywords()));
        e.setDataCategories(cleanList(in.dataCategories()));
        e.setSensitivity(upper(in.sensitivity()));
        e.setContextKey(trim(in.contextKey()));
        e.setConfidence(in.confidence());
        if (in.enabled() != null) {
            e.setEnabled(in.enabled());
        }
        e.setDescription(in.description());
        validate(e);
        requireUniqueName(tenant, e.getName(), id);
        e.setUpdatedAt(LocalDateTime.now());
        DataTagRuleEntity saved = repository.save(e);
        ruleEngine.invalidate(tenant);
        return DataTagRuleDto.from(saved);
    }

    /** Reject a name already used by ANOTHER rule in this tenant (excludeId = the rule being updated, or null). */
    private void requireUniqueName(String tenant, String name, UUID excludeId) {
        if (name == null || name.isBlank()) {
            return; // blank is caught by validate()
        }
        for (DataTagRuleEntity r : repository.findByWsTenantNameAndNameIgnoreCase(tenant, name.trim())) {
            if (excludeId == null || !r.getId().equals(excludeId)) {
                throw new IllegalArgumentException("A rule named \"" + name.trim() + "\" already exists.");
            }
        }
    }

    public void delete(UUID id) {
        String tenant = TenantContext.get();
        repository.delete(find(id, tenant));
        ruleEngine.invalidate(tenant);
    }

    public DataTagRuleDto toggle(UUID id) {
        String tenant = TenantContext.get();
        DataTagRuleEntity e = find(id, tenant);
        e.setEnabled(!e.isEnabled());
        e.setUpdatedAt(LocalDateTime.now());
        DataTagRuleEntity saved = repository.save(e);
        ruleEngine.invalidate(tenant);
        return DataTagRuleDto.from(saved);
    }

    // ── Industry rule-template library (curated packs an admin installs as normal, editable rules) ──

    /** The shipped template catalog grouped by industry, each template marked installed/not for this tenant. */
    public List<RuleTemplatePackView> templates() {
        String tenant = TenantContext.get();
        Set<String> installed = installedTemplateIds(tenant);
        Map<String, List<RuleTemplateView>> byIndustry = new LinkedHashMap<>();
        for (RuleTemplate t : RuleTemplateCatalog.all()) {
            byIndustry.computeIfAbsent(t.industry(), k -> new ArrayList<>())
                    .add(RuleTemplateView.from(t, installed.contains(t.templateId())));
        }
        List<RuleTemplatePackView> out = new ArrayList<>();
        byIndustry.forEach((industry, views) -> {
            String regulation = views.isEmpty() ? null : views.get(0).regulation();
            int installedCount = (int) views.stream().filter(RuleTemplateView::installed).count();
            out.add(new RuleTemplatePackView(industry, regulation, views, installedCount));
        });
        return out;
    }

    /** Install one template as a normal editable CUSTOM rule (idempotent — a re-install returns the existing rule). */
    public DataTagRuleDto installTemplate(String templateId) {
        String tenant = TenantContext.get();
        RuleTemplate t = RuleTemplateCatalog.byId(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rule template."));
        DataTagRuleEntity existing = findByTemplateId(tenant, t.templateId());
        if (existing != null) {
            return DataTagRuleDto.from(existing); // already installed — never duplicate
        }
        DataTagRuleEntity saved = persistTemplate(tenant, t);
        log.info("Rule template '{}' installed for tenant={}", t.templateId(), tenant);
        return DataTagRuleDto.from(saved);
    }

    /** Install every not-yet-installed template in an industry pack; reports counts + any name conflicts skipped. */
    public TemplateInstallResult installPack(String industry) {
        String tenant = TenantContext.get();
        List<RuleTemplate> pack = RuleTemplateCatalog.byIndustry(industry);
        if (pack.isEmpty()) {
            throw new IllegalArgumentException("Unknown template pack.");
        }
        int installed = 0;
        int already = 0;
        List<String> conflicts = new ArrayList<>();
        for (RuleTemplate t : pack) {
            if (findByTemplateId(tenant, t.templateId()) != null) {
                already++;
                continue;
            }
            try {
                persistTemplate(tenant, t);
                installed++;
            } catch (IllegalArgumentException e) {
                conflicts.add(t.name()); // a hand-made rule already uses this name — leave it untouched
            }
        }
        log.info("Template pack '{}' installed for tenant={}: {} new, {} already, {} conflicts",
                industry, tenant, installed, already, conflicts.size());
        return new TemplateInstallResult(installed, already, conflicts);
    }

    /** Build + validate + persist a template as a CUSTOM rule carrying its origin marker. */
    private DataTagRuleEntity persistTemplate(String tenant, RuleTemplate t) {
        DataTagRuleEntity e = DataTagRuleEntity.builder()
                .wsTenantName(tenant)
                .name(trim(t.name()))
                .ruleType(DataTagRuleType.CUSTOM)
                .matchType(normalizeMatchType(t.matchType()))
                .pattern(t.pattern())
                .keywords(cleanList(t.keywords()))
                .dataCategories(cleanList(t.dataCategories()))
                .sensitivity(upper(t.sensitivity()))
                .contextKey(trim(t.contextKey()))
                .enabled(true)
                .description(t.description())
                .sourceTemplateId(t.templateId())
                .createdAt(LocalDateTime.now())
                .build();
        validate(e);
        requireUniqueName(tenant, e.getName(), null);
        DataTagRuleEntity saved = repository.save(e);
        ruleEngine.invalidate(tenant);
        return saved;
    }

    private Set<String> installedTemplateIds(String tenant) {
        Set<String> ids = new HashSet<>();
        for (DataTagRuleEntity r : repository.findByWsTenantNameAndSourceTemplateIdIsNotNull(tenant)) {
            if (r.getSourceTemplateId() != null) {
                ids.add(r.getSourceTemplateId());
            }
        }
        return ids;
    }

    private DataTagRuleEntity findByTemplateId(String tenant, String templateId) {
        return repository.findByWsTenantNameAndSourceTemplateIdIsNotNull(tenant).stream()
                .filter(r -> templateId.equals(r.getSourceTemplateId()))
                .findFirst()
                .orElse(null);
    }

    // ── Built-in detector management (enable/disable + override; never delete — the logic is code) ──

    /** Every built-in detector with its effective state for this tenant: shipped defaults plus any DISABLE/OVERRIDE. */
    public List<EffectiveDetectorView> effectiveDetectors() {
        String tenant = TenantContext.get();
        List<DataTagRuleEntity> rules = repository.findByWsTenantNameOrderByCreatedAtDesc(tenant);
        List<EffectiveDetectorView> out = new ArrayList<>();
        for (DetectorInfo d : ruleEngine.builtinDetectors()) {
            DataTagRuleEntity disable = ruleFor(rules, DataTagRuleType.DISABLE, d.matcher());
            DataTagRuleEntity override = ruleFor(rules, DataTagRuleType.OVERRIDE, d.matcher());
            boolean silenced = disable != null && disable.isEnabled();
            boolean remapped = override != null && override.isEnabled();
            out.add(new EffectiveDetectorView(
                    d.matcher(), d.description(), d.category(), d.defaultSensitivity(),
                    !silenced,
                    disable == null ? null : disable.getId(),
                    remapped ? override.getDataCategories() : null,
                    remapped ? override.getSensitivity() : null,
                    override == null ? null : override.getId()));
        }
        return out;
    }

    /** Turn a built-in detector on/off for this tenant (off = an enabled DISABLE rule; on = no DISABLE rule). */
    public void setDetectorEnabled(String matcher, boolean enabled) {
        String tenant = TenantContext.get();
        requireKnownDetector(matcher);
        List<DataTagRuleEntity> rules = repository.findByWsTenantNameOrderByCreatedAtDesc(tenant);
        DataTagRuleEntity disable = ruleFor(rules, DataTagRuleType.DISABLE, matcher);
        if (enabled) {
            if (disable != null) {
                repository.delete(disable);
            }
        } else if (disable == null) {
            repository.save(DataTagRuleEntity.builder()
                    .wsTenantName(tenant)
                    .name("Disabled built-in: " + matcher)
                    .ruleType(DataTagRuleType.DISABLE)
                    .builtinMatcher(matcher)
                    .enabled(true)
                    .description("Built-in detector turned off by an admin.")
                    .createdAt(LocalDateTime.now())
                    .build());
        } else if (!disable.isEnabled()) {
            disable.setEnabled(true);
            disable.setUpdatedAt(LocalDateTime.now());
            repository.save(disable);
        }
        ruleEngine.invalidate(tenant);
        log.info("Built-in detector '{}' set enabled={} for tenant={}", matcher, enabled, tenant);
    }

    /** Remap a built-in detector's output (categories + sensitivity) for this tenant via an OVERRIDE rule. */
    public void setDetectorOverride(String matcher, List<String> categories, String sensitivity) {
        String tenant = TenantContext.get();
        requireKnownDetector(matcher);
        requireSensitivity(sensitivity, "override");
        List<DataTagRuleEntity> rules = repository.findByWsTenantNameOrderByCreatedAtDesc(tenant);
        DataTagRuleEntity override = ruleFor(rules, DataTagRuleType.OVERRIDE, matcher);
        if (override == null) {
            override = DataTagRuleEntity.builder()
                    .wsTenantName(tenant)
                    .name("Override built-in: " + matcher)
                    .ruleType(DataTagRuleType.OVERRIDE)
                    .builtinMatcher(matcher)
                    .description("Built-in detector remapped by an admin.")
                    .createdAt(LocalDateTime.now())
                    .build();
        }
        override.setDataCategories(cleanList(categories));
        override.setSensitivity(upper(sensitivity));
        override.setEnabled(true);
        override.setUpdatedAt(LocalDateTime.now());
        repository.save(override);
        ruleEngine.invalidate(tenant);
        log.info("Built-in detector '{}' overridden (sensitivity={}) for tenant={}", matcher, upper(sensitivity), tenant);
    }

    /** Remove a built-in detector's OVERRIDE (revert to its shipped category/sensitivity). */
    public void clearDetectorOverride(String matcher) {
        String tenant = TenantContext.get();
        requireKnownDetector(matcher);
        List<DataTagRuleEntity> rules = repository.findByWsTenantNameOrderByCreatedAtDesc(tenant);
        DataTagRuleEntity override = ruleFor(rules, DataTagRuleType.OVERRIDE, matcher);
        if (override != null) {
            repository.delete(override);
            ruleEngine.invalidate(tenant);
            log.info("Built-in detector '{}' override cleared for tenant={}", matcher, tenant);
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** The single DISABLE / OVERRIDE rule a tenant has against a built-in matcher, or null. */
    private DataTagRuleEntity ruleFor(List<DataTagRuleEntity> rules, DataTagRuleType type, String matcher) {
        return rules.stream()
                .filter(r -> r.getRuleType() == type
                        && r.getBuiltinMatcher() != null
                        && matcher.equals(r.getBuiltinMatcher().trim()))
                .findFirst()
                .orElse(null);
    }

    private void requireKnownDetector(String matcher) {
        boolean known = matcher != null
                && ruleEngine.builtinDetectors().stream().anyMatch(d -> d.matcher().equals(matcher));
        if (!known) {
            throw new IllegalArgumentException("Unknown built-in detector.");
        }
    }

    private DataTagRuleEntity find(UUID id, String tenant) {
        return repository.findByIdAndWsTenantName(id, tenant)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found."));
    }

    /** Normalize the CUSTOM match type to REGEX (default) or KEYWORDS; reject anything else. */
    private static String normalizeMatchType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "REGEX";
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!v.equals("REGEX") && !v.equals("KEYWORDS")) {
            throw new IllegalArgumentException("Match type must be REGEX or KEYWORDS.");
        }
        return v;
    }

    private static DataTagRuleType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Rule type is required: CUSTOM, DISABLE, or OVERRIDE.");
        }
        try {
            return DataTagRuleType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Rule type must be CUSTOM, DISABLE, or OVERRIDE.");
        }
    }

    private static void validate(DataTagRuleEntity e) {
        if (isBlank(e.getName())) {
            throw new IllegalArgumentException("A rule name is required.");
        }
        switch (e.getRuleType()) {
            case CUSTOM -> {
                if ("KEYWORDS".equalsIgnoreCase(e.getMatchType())) {
                    if (e.getKeywords() == null || e.getKeywords().isEmpty()) {
                        throw new IllegalArgumentException("A keyword rule needs at least one keyword.");
                    }
                } else {
                    if (isBlank(e.getPattern())) {
                        throw new IllegalArgumentException("A custom rule needs a regex pattern.");
                    }
                    try {
                        Pattern.compile(e.getPattern());
                    } catch (Exception ex) {
                        throw new IllegalArgumentException("Invalid regex pattern: " + ex.getMessage());
                    }
                }
                requireSensitivity(e.getSensitivity(), "custom");
            }
            case DISABLE -> {
                if (isBlank(e.getBuiltinMatcher())) {
                    throw new IllegalArgumentException("A disable rule needs a built-in detector to target.");
                }
            }
            case OVERRIDE -> {
                if (isBlank(e.getBuiltinMatcher())) {
                    throw new IllegalArgumentException("An override rule needs a built-in detector to target.");
                }
                requireSensitivity(e.getSensitivity(), "override");
            }
            default -> throw new IllegalArgumentException("Unknown rule type.");
        }
        if (e.getConfidence() != null && (e.getConfidence() < 0.0 || e.getConfidence() > 1.0)) {
            throw new IllegalArgumentException("Confidence must be between 0 and 1.");
        }
    }

    private static void requireSensitivity(String sensitivity, String kind) {
        if (isBlank(sensitivity)) {
            throw new IllegalArgumentException("A " + kind + " rule needs a sensitivity.");
        }
        boolean known = Arrays.asList(Sensitivity.RANKS).contains(sensitivity.trim().toUpperCase(Locale.ROOT));
        if (!known) {
            throw new IllegalArgumentException("Sensitivity must be PUBLIC, INTERNAL, CONFIDENTIAL, or RESTRICTED.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** Trim, drop blanks, de-dupe the categories; null when none remain (so the column stays clean). */
    private static List<String> cleanList(List<String> raw) {
        if (raw == null) {
            return null;
        }
        List<String> out = raw.stream()
                .map(PostProcessorRuleService::trim)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        return out.isEmpty() ? null : out;
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
