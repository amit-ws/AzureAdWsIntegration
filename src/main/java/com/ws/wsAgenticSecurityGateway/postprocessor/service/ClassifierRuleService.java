package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.Recognition;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.Recognizer;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.RuleOverride;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.RulePolicy;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.Sensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DetectorInfo;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.DataTagRuleEntity;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.DataTagRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a tenant's admin {@code data_tag_rule} rows into a {@link RulePolicy} the classifier applies on top of its
 * built-ins. Compiled once and cached per tenant (short TTL + explicit invalidation on write) so the async
 * classify path never pays a DB round-trip or a regex compile per response. Fail-open: any load error falls back
 * to built-ins only.
 */
@Service
@Slf4j
public class ClassifierRuleService {

    /** Cache TTL — a rule change is picked up on write (invalidate) or within this window on any node. */
    private static final long TTL_MS = 30_000L;
    private static final double DEFAULT_CUSTOM_CONFIDENCE = 0.90;

    private final DataTagRuleRepository repository;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ClassifierRuleService(DataTagRuleRepository repository) {
        this.repository = repository;
    }

    /** The tenant's compiled rule policy (built-ins only if the tenant has no rules, or on any load error). */
    public RulePolicy policyFor(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return RulePolicy.empty();
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(tenant);
        if (cached != null && cached.expiresAt > now) {
            return cached.policy;
        }
        RulePolicy policy;
        try {
            policy = build(tenant);
        } catch (Exception e) {
            log.warn("Egress rule policy load failed for tenant '{}' — using built-ins only: {}", tenant, e.getMessage());
            return RulePolicy.empty();
        }
        cache.put(tenant, new Cached(policy, now + TTL_MS));
        return policy;
    }

    /** Drop the cached policy for a tenant — called by the rule admin API after any create/update/delete/toggle. */
    public void invalidate(String tenant) {
        if (tenant != null) {
            cache.remove(tenant);
        }
    }

    /** The built-in detectors an admin can DISABLE or OVERRIDE — the target list for the rule-authoring UI. */
    public List<DetectorInfo> builtinDetectors() {
        return BUILTIN_DETECTORS;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private RulePolicy build(String tenant) {
        List<DataTagRuleEntity> rules = repository.findByWsTenantNameAndEnabledTrue(tenant);
        List<Recognizer> extras = new ArrayList<>();
        Set<String> disabled = new HashSet<>();
        Map<String, RuleOverride> overrides = new HashMap<>();

        for (DataTagRuleEntity rule : rules) {
            if (rule.getRuleType() == null) {
                continue;
            }
            switch (rule.getRuleType()) {
                case CUSTOM -> {
                    Recognizer recognizer = customRecognizer(rule);
                    if (recognizer != null) {
                        extras.add(recognizer);
                    }
                }
                case DISABLE -> {
                    if (isSet(rule.getBuiltinMatcher())) {
                        disabled.add(rule.getBuiltinMatcher().trim());
                    }
                }
                case OVERRIDE -> {
                    if (isSet(rule.getBuiltinMatcher())) {
                        overrides.put(rule.getBuiltinMatcher().trim(),
                                new RuleOverride(cleanCategories(rule.getDataCategories()),
                                        Sensitivity.rankOf(rule.getSensitivity())));
                    }
                }
            }
        }
        return new RulePolicy(List.copyOf(extras), Set.copyOf(disabled), Map.copyOf(overrides));
    }

    /** Compile a CUSTOM rule into a metadata-only recognizer; a bad regex is skipped (logged), never thrown. */
    private Recognizer customRecognizer(DataTagRuleEntity rule) {
        if (!isSet(rule.getPattern())) {
            return null;
        }
        final Pattern pattern;
        try {
            pattern = Pattern.compile(rule.getPattern());
        } catch (Exception e) {
            log.warn("Skipping custom egress rule '{}' — invalid regex: {}", rule.getName(), e.getMessage());
            return null;
        }
        List<String> cats = cleanCategories(rule.getDataCategories());
        final List<String> categories = cats.isEmpty() ? List.of("CUSTOM") : cats;
        final int floor = Sensitivity.rankOf(rule.getSensitivity());
        final double confidence = rule.getConfidence() != null ? rule.getConfidence() : DEFAULT_CUSTOM_CONFIDENCE;
        final String contextKey = isSet(rule.getContextKey()) ? rule.getContextKey().trim() : null;
        final String matcher = "rule:" + (isSet(rule.getName()) ? rule.getName().trim() : String.valueOf(rule.getId()));

        return text -> {
            List<Recognition> out = new ArrayList<>();
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                // One recognition per category, sharing the match span + matcher id — the classifier collects every
                // category while the detector-evidence count stays span-deduped (so a multi-category match counts once).
                for (String category : categories) {
                    out.add(new Recognition(category, floor, confidence, matcher, contextKey, m.start(), m.end()));
                }
            }
            return out;
        };
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    /** Trim, drop blanks, and de-dupe a rule's categories; never null (empty means "no explicit category"). */
    private static List<String> cleanCategories(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(ClassifierRuleService::isSet).map(String::trim).distinct().toList();
    }

    private record Cached(RulePolicy policy, long expiresAt) {
    }

    /** The shipped built-in detectors (matcher id, default category, default sensitivity, human description). */
    private static final List<DetectorInfo> BUILTIN_DETECTORS = List.of(
            new DetectorInfo("email", "PII", "CONFIDENTIAL", "Email addresses"),
            new DetectorInfo("ssn", "PII", "RESTRICTED", "US Social Security numbers (structure-validated)"),
            new DetectorInfo("credit_card", "FINANCIAL", "RESTRICTED", "Payment cards (Luhn-validated)"),
            new DetectorInfo("iban", "FINANCIAL", "RESTRICTED", "IBAN bank accounts (mod-97)"),
            new DetectorInfo("ip_address", "NETWORK", "INTERNAL", "IPv4 addresses"),
            new DetectorInfo("jwt", "SECRET", "RESTRICTED", "JWT tokens (decode-verified)"),
            new DetectorInfo("aws_access_key", "SECRET", "RESTRICTED", "AWS access key ids"),
            new DetectorInfo("private_key", "SECRET", "RESTRICTED", "PEM private keys"),
            new DetectorInfo("certificate", "SECRET", "RESTRICTED", "PEM certificates"),
            new DetectorInfo("bearer_token", "SECRET", "RESTRICTED", "Authorization bearer tokens"),
            new DetectorInfo("labelled_secret", "SECRET", "RESTRICTED", "key=value secrets (api_key, password, ...)"),
            new DetectorInfo("google_api_key", "SECRET", "RESTRICTED", "Google API keys (AIza...)"),
            new DetectorInfo("github_token", "SECRET", "RESTRICTED", "GitHub tokens (ghp_ / github_pat_)"),
            new DetectorInfo("slack_token", "SECRET", "RESTRICTED", "Slack tokens (xox...)"),
            new DetectorInfo("stripe_key", "SECRET", "RESTRICTED", "Stripe live secret keys"),
            new DetectorInfo("prefixed_key", "SECRET", "RESTRICTED", "Prefixed API keys (sk- / pk- / rk-)"),
            new DetectorInfo("high_entropy", "SECRET", "RESTRICTED", "High-entropy tokens (needs nearby keyword)"),
            new DetectorInfo("prompt_injection", "INJECTION", "-", "Prompt-injection phrases (flag)"));
}
