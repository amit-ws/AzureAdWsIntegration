package com.ws.wsAgenticSecurityGateway.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Determination tests for {@link TokenClassificationService#classifyFromJwtSignals}: given a token's claims,
 * decide HUMAN vs NHI (AUTOMATED_AGENT). Each case models a real IdP's token shape so the multi-IdP
 * signal chain is proven, not assumed. These are pure (no Spring context) — the method is a pure function.
 */
class TokenClassificationServiceTest {

    private static final String NHI = TokenClassificationService.TOKEN_TYPE_AUTOMATED;   // AUTOMATED_AGENT
    private static final String HUMAN = TokenClassificationService.TOKEN_TYPE_HUMAN;      // HUMAN_DELEGATED

    private final TokenClassificationService svc =
            new TokenClassificationService(new TokenClassificationProperties());

    /** Classify a token. customClaims mirrors allClaims — the chain only reads ws_gateway_* from it. */
    private String classify(Map<String, Object> claims) {
        return svc.classifyFromJwtSignals(claims, claims).tokenType();
    }

    private String signal(Map<String, Object> claims) {
        return svc.classifyFromJwtSignals(claims, claims).matchedSignal();
    }

    private static Map<String, Object> claims(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---- NHI (machine / service-account) shapes -----------------------------------------------------

    @Test
    void keycloakServiceAccount_isNhi_viaStructure() {
        // The exact shape that was misclassified: UUID sub, marker in preferred_username, no gty/idtyp/amr.
        Map<String, Object> t = claims(
                "sub", "85c5929e-6af5-4293-b5ca-474d7b6f7c39",
                "azp", "advisor",
                "client_id", "advisor",
                "preferred_username", "service-account-advisor",
                "typ", "Bearer");
        assertEquals(NHI, classify(t));
        assertEquals("SIGNAL_5_SERVICE_ACCOUNT_PRINCIPAL", signal(t));
    }

    @Test
    void azureAdAppToken_isNhi() {
        assertEquals(NHI, classify(claims("idtyp", "app", "azp", "some-app-guid")));
    }

    @Test
    void oktaClientCredentials_subEqualsClient_isNhi() {
        Map<String, Object> t = claims("sub", "0oaClient123", "client_id", "0oaClient123");
        assertEquals(NHI, classify(t));
        assertEquals("SIGNAL_5_SUBJECT_IS_CLIENT", signal(t));
    }

    @Test
    void genericGtyClientCredentials_isNhi() {
        // gty is authoritative and wins even over a human-looking username.
        assertEquals(NHI, classify(claims("gty", "client_credentials", "azp", "svc", "preferred_username", "svc")));
    }

    @Test
    void headlessClientNoHumanIdentity_isNhi_viaBackstop() {
        Map<String, Object> t = claims("azp", "headless-worker", "scope", "api.read");
        assertEquals(NHI, classify(t));
        assertEquals("SIGNAL_6B_CLIENT_WITHOUT_HUMAN_IDENTITY", signal(t));
    }

    @Test
    void explicitClaimWins_evenWithHumanLookingClaims() {
        assertEquals(NHI, classify(claims(
                "ws_gateway_token_type", "AUTOMATED_AGENT",
                "preferred_username", "amit", "email", "amit@example.com")));
    }

    // ---- HUMAN shapes -------------------------------------------------------------------------------

    @Test
    void keycloakInteractiveHuman_isHuman() {
        Map<String, Object> t = claims(
                "sub", "11111111-2222-3333-4444-555555555555",
                "azp", "agent-console",
                "preferred_username", "amit",
                "email", "amit@example.com",
                "given_name", "Amit");
        assertEquals(HUMAN, classify(t));
        assertEquals("SIGNAL_6_HUMAN_USERNAME", signal(t));
    }

    @Test
    void oboDelegation_isHuman() {
        // A delegated (act) token represents a human acting through an agent.
        assertEquals(HUMAN, classify(claims("act", Map.of("sub", "agent-x"), "azp", "advisor")));
    }

    @Test
    void humanMinimalScope_withUsername_isHuman() {
        // Interactive login, minimal scopes (no email/name) but preferred_username + auth_time present.
        assertEquals(HUMAN, classify(claims(
                "azp", "webapp", "preferred_username", "jdoe", "auth_time", 1786287619L)));
    }

    @Test
    void humanAmr_isHuman() {
        assertEquals(HUMAN, classify(claims(
                "amr", List.of("pwd", "mfa"), "azp", "webapp", "preferred_username", "jdoe")));
    }

    // ---- conservative fallback ----------------------------------------------------------------------

    @Test
    void emptyish_token_failsSafeToHuman() {
        Map<String, Object> t = claims("iss", "https://idp.example.com");
        assertEquals(HUMAN, classify(t));
        assertEquals("SIGNAL_7_CONSERVATIVE_DEFAULT", signal(t));
    }
}
