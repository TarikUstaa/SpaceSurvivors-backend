package com.tarikusta.spacesurvivors.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The algorithm, checked against RFC 6238's published test vectors — the only honest way to know a
 * hand-written TOTP agrees with every authenticator app.
 */
class TotpTest {

    /** RFC 6238 Appendix B, SHA-1 key: the ASCII bytes of "12345678901234567890". */
    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("matches RFC 6238's SHA-1 vectors (last six of the eight published digits)")
    void rfcVectors() {
        assertThat(Totp.codeFor(RFC_KEY, 59L / 30)).isEqualTo("287082");          // 94287082
        assertThat(Totp.codeFor(RFC_KEY, 1111111109L / 30)).isEqualTo("081804");  // 07081804
        assertThat(Totp.codeFor(RFC_KEY, 1234567890L / 30)).isEqualTo("005924");  // 89005924
        assertThat(Totp.codeFor(RFC_KEY, 2000000000L / 30)).isEqualTo("279037");  // 69279037
    }

    @Test
    @DisplayName("base32 round-trips, and encodes the RFC key the way apps expect")
    void base32() {
        assertThat(Totp.base32(RFC_KEY)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.unbase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")).isEqualTo(RFC_KEY);
        String secret = Totp.newSecret();
        assertThat(Totp.base32(Totp.unbase32(secret))).isEqualTo(secret);
    }

    @Test
    @DisplayName("a code is accepted one step either side of now, and not two")
    void driftWindow() {
        String secret = Totp.base32(RFC_KEY);
        long now = 1_234_567_890L;
        long step = now / 30;

        assertThat(Totp.matchingStep(secret, Totp.codeFor(RFC_KEY, step), now)).hasValue(step);
        assertThat(Totp.matchingStep(secret, Totp.codeFor(RFC_KEY, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.matchingStep(secret, Totp.codeFor(RFC_KEY, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.matchingStep(secret, Totp.codeFor(RFC_KEY, step - 2), now)).isEmpty();
    }

    @Test
    @DisplayName("anything that is not six digits is refused before any hashing")
    void malformed() {
        String secret = Totp.newSecret();
        assertThat(Totp.matchingStep(secret, null, 0)).isEmpty();
        assertThat(Totp.matchingStep(secret, "12345", 0)).isEmpty();
        assertThat(Totp.matchingStep(secret, "12a456", 0)).isEmpty();
    }

    @Test
    @DisplayName("the otpauth link carries the secret and the issuer")
    void uri() {
        assertThat(Totp.otpauthUri("SpaceSurvivors", "tarik", "ABC"))
                .isEqualTo("otpauth://totp/SpaceSurvivors:tarik?secret=ABC&issuer=SpaceSurvivors"
                           + "&algorithm=SHA1&digits=6&period=30");
    }
}
