package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import java.util.Set;

/**
 * Standard deny/allow lists that keep the built-in recognizers high-precision: well-known test/sample values are
 * suppressed (never a real leak), documentation IP ranges are suppressed, and private/loopback IPs are treated
 * as low-value internal topology rather than a data breach. Sources: card-processor canonical test PANs,
 * RFC 2606/6761 (example/test domains), RFC 5737 (documentation IPv4), RFC 1918 (private IPv4).
 */
final class DenyLists {

    private DenyLists() {
    }

    /** Card-processor canonical test PANs (all pass Luhn) — a match here is sample data, not a real card. */
    private static final Set<String> TEST_CARDS = Set.of(
            "4111111111111111", "4012888888881881", "4222222222222", "4242424242424242", "4000056655665556",
            "5555555555554444", "5105105105105100", "5200828282828210", "2223003122003222", "2223000048410010",
            "378282246310005", "371449635398431", "340000000000009",
            "6011111111111117", "6011000990139424", "6011111111111004",
            "30569309025904", "38520000023237", "3056930009020004", "36227206271667",
            "3530111333300000", "3566002020360505", "6200000000000005");

    static boolean isTestCard(String digitsOnly) {
        return TEST_CARDS.contains(digitsOnly);
    }

    /** Reserved example/test domains (RFC 2606/6761) + common placeholders — an email here is a sample. */
    static boolean isExampleDomain(String domainLower) {
        if (domainLower == null) {
            return false;
        }
        switch (domainLower) {
            case "example.com":
            case "example.org":
            case "example.net":
            case "example.edu":
            case "domain.com":
            case "yourdomain.com":
            case "email.com":
            case "test.com":
            case "bar.com":
                return true;
            default:
                return domainLower.endsWith(".test") || domainLower.endsWith(".example")
                        || domainLower.endsWith(".invalid") || domainLower.endsWith(".localhost")
                        || domainLower.equals("localhost");
        }
    }

    /** RFC 5737 documentation ranges — a match is a docs/sample address, not real infrastructure. */
    static boolean isDocumentationIp(int[] octets) {
        return (octets[0] == 192 && octets[1] == 0 && octets[2] == 2)          // 192.0.2.0/24
                || (octets[0] == 198 && octets[1] == 51 && octets[2] == 100)   // 198.51.100.0/24
                || (octets[0] == 203 && octets[1] == 0 && octets[2] == 113);   // 203.0.113.0/24
    }

    /** RFC 1918 private + loopback + link-local + CGNAT — internal topology, low value (down-rank, don't suppress). */
    static boolean isPrivateIp(int[] octets) {
        int a = octets[0];
        int b = octets[1];
        return a == 10
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || a == 127
                || (a == 169 && b == 254)
                || (a == 100 && b >= 64 && b <= 127)
                || a == 0;
    }
}
