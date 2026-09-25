package com.ws.wsAgenticSecurityGateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ground-truth suite: real-world token shapes from many IdPs (Azure/Okta/Auth0/Google/Keycloak/ForgeRock),
 * surfaced by an adversarial review, each with its TRUE human-vs-NHI type. Runs every shape through the real
 * classifier so "the model thinks this misclassifies" becomes "the compiled code provably does/doesn't".
 * Cases live in test resources so they read like data, not code.
 */
class TokenClassificationIdpCasesTest {

    private final TokenClassificationService svc =
            new TokenClassificationService(new TokenClassificationProperties());
    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> classifiesEveryRealWorldTokenShape() throws Exception {
        List<Map<String, Object>> cases = mapper.readValue(
                getClass().getResourceAsStream("/idp_misclassification_cases.json"), List.class);

        return cases.stream().map(c -> {
            String label = (String) c.get("label");
            String expected = (String) c.get("trueType");
            Map<String, Object> claims = (Map<String, Object>) c.get("claims");
            return DynamicTest.dynamicTest(label, () -> {
                TokenClassificationService.ClassificationResult r =
                        svc.classifyFromJwtSignals(claims, claims);
                assertEquals(expected, r.tokenType(),
                        () -> label + " → fired " + r.matchedSignal() + " (got " + r.tokenType() + ")");
            });
        }).collect(Collectors.toList()).stream();
    }
}
