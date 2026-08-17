package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in, deterministic recognizer set — the Tier-1 core of the post-processor: strong regexes, checksum
 * validation (Luhn, IBAN mod-97), high-entropy secret detection, and vendor-prefixed API-key patterns. Each
 * recognizer returns metadata-only {@link Recognition}s (never the matched value) and applies its own deny-list
 * suppression up front. Confidence gating, context boosting, and aggregation are the pipeline's job
 * ({@link EgressClassifier}).
 *
 * <p>Value-driven and name-agnostic: a field literally containing an SSN is PII regardless of its key.
 */
final class BuiltInRecognizers {

    private BuiltInRecognizers() {
    }

    // --- structured identifiers ---
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern SSN = Pattern.compile("\\b(\\d{3})-(\\d{2})-(\\d{4})\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");
    private static final Pattern CARD_CANDIDATE = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    private static final Pattern IBAN_CANDIDATE = Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern ENTROPY_TOKEN = Pattern.compile("[A-Za-z0-9+/=_-]{20,}");

    // --- secrets (strong, low-false-positive markers) ---
    private static final Pattern AWS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
    private static final Pattern PRIVATE_KEY =
            Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----");
    private static final Pattern CERTIFICATE = Pattern.compile("-----BEGIN CERTIFICATE-----");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{20,}={0,2}");
    private static final Pattern LABELLED_SECRET = Pattern.compile(
            "(?i)(?:api[_-]?key|secret|token|password|passwd|access[_-]?key)[\"']?\\s*[:=]\\s*"
            + "[\"']?[A-Za-z0-9._\\-/+]{8,}");

    // --- vendor-prefixed tokens (exact charset + length → near-zero false positive) ---
    private static final Pattern GOOGLE_API = Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b");
    private static final Pattern GH_PAT = Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}\\b");
    private static final Pattern GH_FINE = Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{82}\\b");
    private static final Pattern SLACK = Pattern.compile("\\bxox[baprs]-[0-9A-Za-z-]{10,48}\\b");
    private static final Pattern STRIPE = Pattern.compile("\\b(?:sk|rk)_live_[0-9A-Za-z]{16,}\\b");
    private static final Pattern PREFIXED_KEY = Pattern.compile("\\b(?:sk|pk|rk)-[A-Za-z0-9]{16,}\\b");

    // --- prompt injection (V1 flags; V2 neutralizes) ---
    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(ignore\\s+(?:all\\s+)?(?:the\\s+)?(?:previous|prior|above)\\s+instructions"
            + "|disregard\\s+(?:the\\s+)?(?:previous|prior|above|system)"
            + "|you\\s+are\\s+now\\b"
            + "|system\\s+prompt\\b"
            + "|reveal\\s+(?:your\\s+)?(?:system\\s+)?prompt)");

    /** The ordered built-in recognizer list. Admin/ML recognizers append to a copy of this in the pipeline. */
    static List<Recognizer> all() {
        List<Recognizer> r = new ArrayList<>();
        r.add(BuiltInRecognizers::email);
        r.add(BuiltInRecognizers::ssn);
        r.add(BuiltInRecognizers::creditCard);
        r.add(BuiltInRecognizers::iban);
        r.add(BuiltInRecognizers::ipv4);
        r.add(BuiltInRecognizers::jwt);
        r.add(BuiltInRecognizers::secrets);
        r.add(BuiltInRecognizers::vendorTokens);
        r.add(BuiltInRecognizers::entropySecret);
        r.add(BuiltInRecognizers::injection);
        return r;
    }

    private static List<Recognition> email(String text) {
        List<Recognition> out = new ArrayList<>();
        Matcher m = EMAIL.matcher(text);
        while (m.find()) {
            String domain = m.group(1).toLowerCase(Locale.ROOT);
            if (DenyLists.isExampleDomain(domain)) {
                continue; // sample/placeholder address, not a real leak
            }
            out.add(new Recognition("PII", Sensitivity.CONFIDENTIAL, 0.60, "email", null, m.start(), m.end()));
        }
        return out;
    }

    private static List<Recognition> ssn(String text) {
        List<Recognition> out = new ArrayList<>();
        Matcher m = SSN.matcher(text);
        while (m.find()) {
            int area = Integer.parseInt(m.group(1));
            int group = Integer.parseInt(m.group(2));
            int serial = Integer.parseInt(m.group(3));
            // Reject structurally-invalid SSNs (never issued) — cuts a large false-positive class from ddd-dd-dddd.
            if (area == 0 || area == 666 || area >= 900 || group == 0 || serial == 0) {
                continue;
            }
            out.add(new Recognition("PII", Sensitivity.RESTRICTED, 0.85, "ssn", "ssn", m.start(), m.end()));
        }
        return out;
    }

    private static List<Recognition> creditCard(String text) {
        List<Recognition> out = new ArrayList<>();
        Matcher m = CARD_CANDIDATE.matcher(text);
        while (m.find()) {
            String digits = m.group().replaceAll("[^0-9]", "");
            if (digits.length() < 13 || digits.length() > 19 || !luhn(digits) || DenyLists.isTestCard(digits)) {
                continue;
            }
            // A real card is both financial data and PII.
            out.add(new Recognition("FINANCIAL", Sensitivity.RESTRICTED, 0.85, "credit_card", "card", m.start(), m.end()));
            out.add(new Recognition("PII", Sensitivity.RESTRICTED, 0.85, "credit_card", "card", m.start(), m.end()));
        }
        return out;
    }

    private static List<Recognition> iban(String text) {
        List<Recognition> out = new ArrayList<>();
        Matcher m = IBAN_CANDIDATE.matcher(text);
        while (m.find()) {
            if (ibanValid(m.group())) {
                out.add(new Recognition("FINANCIAL", Sensitivity.RESTRICTED, 0.85, "iban", "iban", m.start(), m.end()));
            }
        }
        return out;
    }

    private static List<Recognition> ipv4(String text) {
        List<Recognition> out = new ArrayList<>();
        Matcher m = IPV4.matcher(text);
        while (m.find()) {
            int[] oct = {
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)) };
            if (oct[0] > 255 || oct[1] > 255 || oct[2] > 255 || oct[3] > 255) {
                continue; // not a valid dotted quad (e.g. a version string)
            }
            if (DenyLists.isDocumentationIp(oct)) {
                continue; // RFC 5737 documentation address — sample, not real infra
            }
            // Public and private both register at INTERNAL — an IP is a topology signal, not a data breach.
            out.add(new Recognition("NETWORK", Sensitivity.INTERNAL, 0.60, "ip_address", null, m.start(), m.end()));
        }
        return out;
    }

    private static List<Recognition> jwt(String text) {
        List<Recognition> out = new ArrayList<>();
        Matcher m = JWT.matcher(text);
        while (m.find()) {
            double conf = jwtDecodes(m.group()) ? 0.90 : 0.60;
            out.add(new Recognition("SECRET", Sensitivity.RESTRICTED, conf, "jwt", "secret", m.start(), m.end()));
        }
        return out;
    }

    private static List<Recognition> secrets(String text) {
        List<Recognition> out = new ArrayList<>();
        addAll(out, AWS_KEY, text, 0.90, "aws_access_key");
        addAll(out, PRIVATE_KEY, text, 0.95, "private_key");
        addAll(out, CERTIFICATE, text, 0.95, "certificate");
        addAll(out, BEARER, text, 0.90, "bearer_token");
        addAll(out, LABELLED_SECRET, text, 0.85, "labelled_secret");
        return out;
    }

    private static List<Recognition> vendorTokens(String text) {
        List<Recognition> out = new ArrayList<>();
        addAll(out, GOOGLE_API, text, 0.90, "google_api_key");
        addAll(out, GH_PAT, text, 0.90, "github_token");
        addAll(out, GH_FINE, text, 0.90, "github_token");
        addAll(out, SLACK, text, 0.90, "slack_token");
        addAll(out, STRIPE, text, 0.90, "stripe_key");
        addAll(out, PREFIXED_KEY, text, 0.85, "prefixed_key");
        return out;
    }

    private static List<Recognition> entropySecret(String text) {
        List<Recognition> out = new ArrayList<>();
        Matcher m = ENTROPY_TOKEN.matcher(text);
        while (m.find()) {
            String tok = m.group();
            if (Entropy.looksRandom(tok) && Entropy.ratio(tok) >= 0.75) {
                // Base 0.50 — needs a nearby secret keyword to clear the gate, so random-looking ids/hashes
                // do not escalate the response on their own.
                out.add(new Recognition("SECRET", Sensitivity.RESTRICTED, 0.50, "high_entropy", "secret", m.start(), m.end()));
            }
        }
        return out;
    }

    private static List<Recognition> injection(String text) {
        List<Recognition> out = new ArrayList<>();
        Matcher m = INJECTION.matcher(text);
        if (m.find()) {
            out.add(new Recognition("INJECTION", Sensitivity.PUBLIC, 1.0, "prompt_injection", null, m.start(), m.end()));
        }
        return out;
    }

    // --- helpers ---

    private static void addAll(List<Recognition> out, Pattern p, String text, double conf, String matcher) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(new Recognition("SECRET", Sensitivity.RESTRICTED, conf, matcher, "secret", m.start(), m.end()));
        }
    }

    private static boolean luhn(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (alt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    /** IBAN mod-97 checksum (ISO 7064): move the first four chars to the end, map letters A→10…Z→35, mod 97 == 1. */
    private static boolean ibanValid(String raw) {
        String s = raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (s.length() < 15 || s.length() > 34) {
            return false;
        }
        String r = s.substring(4) + s.substring(0, 4);
        int rem = 0;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            int v;
            if (c >= '0' && c <= '9') {
                v = c - '0';
            } else if (c >= 'A' && c <= 'Z') {
                v = c - 'A' + 10;
            } else {
                return false;
            }
            rem = v < 10 ? (rem * 10 + v) % 97 : (rem * 100 + v) % 97;
        }
        return rem == 1;
    }

    /** A JWT-shaped token whose first two segments base64url-decode to JSON, with an {@code alg} in the header. */
    private static boolean jwtDecodes(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            String header = new String(Base64.getUrlDecoder().decode(pad(parts[0])), StandardCharsets.UTF_8);
            String payload = new String(Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8);
            return header.trim().startsWith("{") && header.contains("alg") && payload.trim().startsWith("{");
        } catch (Exception e) {
            return false;
        }
    }

    private static String pad(String b64url) {
        int m = b64url.length() % 4;
        return m == 0 ? b64url : b64url + "====".substring(m);
    }
}
