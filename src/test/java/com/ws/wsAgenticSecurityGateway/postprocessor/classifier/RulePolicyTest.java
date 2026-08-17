package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how admin {@link RulePolicy} adjustments layer onto the built-in classifier: custom recognizers add tags,
 * DISABLE suppresses a built-in, OVERRIDE remaps a built-in's category/sensitivity, and an empty policy is
 * identical to the no-arg classify.
 */
class RulePolicyTest {

    private final EgressClassifier classifier = new EgressClassifier();

    private static Recognizer regexRule(String regex, String category, String sensitivity, String matcher) {
        Pattern p = Pattern.compile(regex);
        int floor = Sensitivity.rankOf(sensitivity);
        return text -> {
            List<Recognition> out = new ArrayList<>();
            Matcher m = p.matcher(text);
            while (m.find()) {
                out.add(new Recognition(category, floor, 0.9, matcher, null, m.start(), m.end()));
            }
            return out;
        };
    }

    @Test
    void customRule_tagsMatches() {
        RulePolicy policy = new RulePolicy(
                List.of(regexRule("ACME-\\d{4}", "PROJECT", "INTERNAL", "rule:acme")),
                Set.of(), Map.of());
        DetectionResult r = classifier.classify("ticket ACME-1234 opened", policy);
        assertThat(r.categories()).contains("PROJECT");
        assertThat(r.sensitivity()).isEqualTo("INTERNAL");
        assertThat(r.detectors()).containsKey("rule:acme");
    }

    @Test
    void disableRule_suppressesBuiltin() {
        RulePolicy policy = new RulePolicy(List.of(), Set.of("email"), Map.of());
        DetectionResult r = classifier.classify("Contact jane.doe@acme-corp.com for details", policy);
        assertThat(r.categories()).doesNotContain("PII");
        assertThat(r.detectors()).doesNotContainKey("email");
        assertThat(r.sensitivity()).isEqualTo("PUBLIC");
    }

    @Test
    void overrideRule_downgradesSensitivity() {
        // Email is CONFIDENTIAL by default; an admin override drops it to INTERNAL.
        RulePolicy policy = new RulePolicy(List.of(), Set.of(),
                Map.of("email", new RuleOverride(null, Sensitivity.rankOf("INTERNAL"))));
        DetectionResult r = classifier.classify("Contact jane.doe@acme-corp.com for details", policy);
        assertThat(r.categories()).contains("PII");
        assertThat(r.sensitivity()).isEqualTo("INTERNAL");
    }

    @Test
    void overrideRule_canRemapCategory() {
        RulePolicy policy = new RulePolicy(List.of(), Set.of(),
                Map.of("ip_address", new RuleOverride("INFRA", Sensitivity.rankOf("CONFIDENTIAL"))));
        DetectionResult r = classifier.classify("origin host 10.0.12.44 responded", policy);
        assertThat(r.categories()).contains("INFRA");
        assertThat(r.categories()).doesNotContain("NETWORK");
        assertThat(r.sensitivity()).isEqualTo("CONFIDENTIAL");
    }

    @Test
    void emptyPolicy_matchesNoArgClassify() {
        String text = "SSN 123-45-6789 and jane@acme-corp.com";
        DetectionResult withEmpty = classifier.classify(text, RulePolicy.empty());
        DetectionResult noArg = classifier.classify(text);
        assertThat(withEmpty.sensitivity()).isEqualTo(noArg.sensitivity());
        assertThat(withEmpty.categories()).isEqualTo(noArg.categories());
    }
}
