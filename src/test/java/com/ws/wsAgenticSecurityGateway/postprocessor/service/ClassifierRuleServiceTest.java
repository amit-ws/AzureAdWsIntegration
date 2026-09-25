package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.DetectionResult;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.EgressClassifier;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.RulePolicy;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.DataTagRuleEntity;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.DataTagRuleType;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.DataTagRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the KEYWORDS match type: a plain word list compiles to a safe matcher — whole-word, case-insensitive, with
 * special characters escaped — so a non-technical admin can't produce a broken or over-matching rule.
 */
class ClassifierRuleServiceTest {

    private final DataTagRuleRepository repo = mock(DataTagRuleRepository.class);
    private final ClassifierRuleService svc = new ClassifierRuleService(repo);
    private final EgressClassifier classifier = new EgressClassifier();

    private static DataTagRuleEntity keywordRule(String name, List<String> keywords, String category) {
        return DataTagRuleEntity.builder()
                .wsTenantName("t1").name(name).ruleType(DataTagRuleType.CUSTOM)
                .matchType("KEYWORDS").keywords(keywords)
                .dataCategories(List.of(category)).sensitivity("INTERNAL").enabled(true)
                .build();
    }

    @Test
    void keywordRule_matchesWholeWordCaseInsensitive_notPartial() {
        when(repo.findByWsTenantNameAndEnabledTrue("t1"))
                .thenReturn(List.of(keywordRule("codenames", List.of("Bluebird", "Falcon"), "PROJECT")));
        RulePolicy policy = svc.policyFor("t1");

        // Whole-word, case-insensitive → matches "bluebird" even lowercased.
        DetectionResult hit = classifier.classify("meet at bluebird HQ today", policy);
        assertThat(hit.categories()).contains("PROJECT");
        assertThat(hit.sensitivity()).isEqualTo("INTERNAL");
        assertThat(hit.detectors()).containsKey("rule:codenames");

        // Partial word must NOT match ("falconer" contains "falcon").
        DetectionResult noHit = classifier.classify("the falconer trains birds", policy);
        assertThat(noHit.categories()).doesNotContain("PROJECT");
    }

    @Test
    void keywordRule_escapesSpecialCharacters() {
        when(repo.findByWsTenantNameAndEnabledTrue("t1"))
                .thenReturn(List.of(keywordRule("vendors", List.of("AT&T"), "VENDOR")));

        DetectionResult r = classifier.classify("contract with AT&T was signed", svc.policyFor("t1"));
        assertThat(r.categories()).contains("VENDOR");
    }

    @Test
    void keywordRule_withNoKeywords_isSkipped() {
        when(repo.findByWsTenantNameAndEnabledTrue("t1"))
                .thenReturn(List.of(keywordRule("empty", List.of(), "PROJECT")));

        DetectionResult r = classifier.classify("nothing to see here", svc.policyFor("t1"));
        assertThat(r.categories()).doesNotContain("PROJECT");
        assertThat(r.detectors()).doesNotContainKey("rule:empty");
    }
}
