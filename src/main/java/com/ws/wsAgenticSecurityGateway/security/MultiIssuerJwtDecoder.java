package com.ws.wsAgenticSecurityGateway.security;

import com.nimbusds.jwt.SignedJWT;
import com.ws.wsAgenticSecurityGateway.authConfig.service.DelegatingJwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * The gateway's inbound JWT decoder: multi-issuer. It routes a token by its {@code iss} claim to the right
 * verifier — the gateway's own {@link StsJwtDecoder} for a gateway-minted OBO (the multi-hop A2A return leg),
 * or the configured IdP decoder ({@link DelegatingJwtDecoder}, e.g. Keycloak) for a first-hop external token.
 *
 * <p>Routing reads the (as-yet unverified) issuer only to pick a decoder; the chosen decoder then performs the
 * full cryptographic + claim validation, so a forged issuer buys nothing. This is what lets the gateway accept
 * its own short-TTL delegation credential back on hop 2 and extend the {@code act_chain}, without ever
 * broadening trust of the external IdP.
 */
@Component
public class MultiIssuerJwtDecoder implements JwtDecoder {

    private final DelegatingJwtDecoder idpDecoder;
    private final StsJwtDecoder stsDecoder;

    public MultiIssuerJwtDecoder(DelegatingJwtDecoder idpDecoder, StsJwtDecoder stsDecoder) {
        this.idpDecoder = idpDecoder;
        this.stsDecoder = stsDecoder;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer = peekIssuer(token);
        if (stsDecoder.handles(issuer)) {
            return stsDecoder.decode(token); // gateway-minted OBO (multi-hop return leg)
        }
        return idpDecoder.decode(token);     // external IdP (first hop)
    }

    /** Read the issuer without verifying — only to choose a decoder; the decoder does the real validation. */
    private static String peekIssuer(String token) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
        } catch (Exception e) {
            return null; // let the IdP decoder surface the canonical malformed-token error
        }
    }
}
