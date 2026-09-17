package com.tarikusta.spacesurvivors.admin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.OptionalLong;

/**
 * Time-based one-time passwords (RFC 6238), the codes an authenticator app shows.
 *
 * <p><b>Written here rather than pulled in as a library</b> because the whole algorithm is the
 * forty lines below, every authenticator app implements exactly these defaults (HMAC-SHA1, 30-second
 * steps, six digits), and a dependency for it would be a supply-chain question to answer about code
 * this short. {@code TotpTest} checks it against the RFC's own test vectors, which is the part that
 * matters.</p>
 *
 * <p><b>How it works.</b> The secret is shared once, when the app scans or types it. After that,
 * both sides count 30-second steps since 1970, HMAC the step number with the secret, and cut six
 * digits out of the result. Nothing travels between them; they agree because they compute the same
 * thing from the same two inputs.</p>
 */
final class Totp {

    static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;   // 160 bits, what RFC 4226 recommends

    /**
     * How many steps either side of now a code may be from. One: a phone clock a little off, or a
     * code typed just as it rolled over, still works — and the window a stolen code is good for
     * stays about a minute and a half.
     */
    private static final int DRIFT_STEPS = 1;

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    /**
     * The step a code belongs to, if it is valid for any step within the drift window — or empty.
     * The caller compares the step with the last one accepted, which is what refuses a replay.
     */
    static OptionalLong matchingStep(String base32Secret, String code, long epochSeconds) {
        if (code == null || code.length() != DIGITS || !code.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        byte[] key = unbase32(base32Secret);
        long now = epochSeconds / STEP_SECONDS;
        for (long step = now - DRIFT_STEPS; step <= now + DRIFT_STEPS; step++) {
            // Constant-time comparison: a byte-by-byte equals returns sooner the earlier the first
            // wrong digit is, and that timing difference is information.
            if (MessageDigest.isEqual(codeFor(key, step).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /** The code for one step — RFC 4226's HOTP with the step as the counter. */
    static String codeFor(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());

            // "Dynamic truncation": the low nibble of the last byte picks where to read 31 bits.
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);

            int modulus = (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", binary % modulus);
        } catch (GeneralSecurityException e) {
            // HmacSHA1 is required of every Java runtime; this cannot happen on a working JVM.
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /**
     * What an authenticator app scans (as a QR code) or accepts as a link. Most apps also take the
     * secret typed in by hand, which is what the page offers — there is no QR library here.
     */
    static String otpauthUri(String issuer, String account, String base32Secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + encode(issuer)
               + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    // ── base32 (RFC 4648, no padding — the form authenticator apps expect) ─────────────

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }

    static byte[] unbase32(String text) {
        String clean = text.replace(" ", "").replace("=", "").toUpperCase(java.util.Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
            if (value < 0) {
                throw new IllegalArgumentException("not base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return out.array();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
