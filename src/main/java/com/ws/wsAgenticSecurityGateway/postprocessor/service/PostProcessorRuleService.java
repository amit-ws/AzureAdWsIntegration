package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.Sensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DataTagRuleDto;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DetectorInfo;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.DataTagRuleEntity;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.DataTagRuleType;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.DataTagRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
                .pattern(in.pattern())
                .dataCategory(trim(in.dataCategory()))
                .sensitivity(upper(in.sensitivity()))
                .contextKey(trim(in.contextKey()))
                .confidence(in.confidence())
                .enabled(in.enabled() == null || in.enabled())
                .description(in.description())
                .createdAt(LocalDateTime.now())
                .build();
        validate(e);
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
        e.setPattern(in.pattern());
        e.setDataCategory(trim(in.dataCategory()));
        e.setSensitivity(upper(in.sensitivity()));
        e.setContextKey(trim(in.contextKey()));
        e.setConfidence(in.confidence());
        if (in.enabled() != null) {
            e.setEnabled(in.enabled());
        }
        e.setDescription(in.description());
        validate(e);
        e.setUpdatedAt(LocalDateTime.now());
        DataTagRuleEntity saved = repository.save(e);
        ruleEngine.invalidate(tenant);
        return DataTagRuleDto.from(saved);
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

    // ── Internals ────────────────────────────────────────────────────────────

    private DataTagRuleEntity find(UUID id, String tenant) {
        return repository.findByIdAndWsTenantName(id, tenant)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found."));
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
                if (isBlank(e.getPattern())) {
                    throw new IllegalArgumentException("A custom rule needs a regex pattern.");
                }
                try {
                    Pattern.compile(e.getPattern());
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Invalid regex pattern: " + ex.getMessage());
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

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
