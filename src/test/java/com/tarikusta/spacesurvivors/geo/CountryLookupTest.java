package com.tarikusta.spacesurvivors.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the lookup does when it has no database — which is its state on every developer's
 * machine, in this whole test suite, and in any deployment built without a license key.
 *
 * <p>There is deliberately no test here that a real address resolves to a real country.
 * That would need MaxMind's file, which is licensed and not in this repository, so such a
 * test could only ever pass on a machine that happened to have one. What matters and can be
 * pinned down is the contract every caller depends on: never throw, and answer null rather
 * than a guess.</p>
 */
class CountryLookupTest {

    @Test
    @DisplayName("an unconfigured database is a working state, not a failure")
    void startsWithoutADatabase() {
        assertThatCode(() -> new CountryLookup("")).doesNotThrowAnyException();
        assertThat(new CountryLookup("").of("8.8.8.8")).isNull();
    }

    @Test
    @DisplayName("a configured database that is not there is reported, not fatal")
    void survivesAMissingFile() {
        CountryLookup lookup = new CountryLookup("/no/such/GeoLite2-Country.mmdb");

        assertThat(lookup.of("8.8.8.8")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",        // loopback — every local sign-in arrives on one of these two
            "::1",
            "10.0.0.7",         // private ranges: a LAN address has no country to find
            "192.168.1.10",
            "172.16.4.2",
            "169.254.10.1",     // link-local
            "0.0.0.0",
    })
    @DisplayName("addresses that cannot belong to a country answer null")
    void answersNullForAddressesWithNoCountry(String ip) {
        assertThat(new CountryLookup("").of(ip)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-an-address", "999.999.999.999", "example.com"})
    @DisplayName("junk is refused rather than resolved")
    void answersNullForJunk(String ip) {
        // "example.com" is the one that matters: a hostname must never reach a DNS lookup
        // from here, because that would turn a stored value into an outbound request.
        assertThat(new CountryLookup("").of(ip)).isNull();
    }

    @Test
    void answersNullForNoAddressAtAll() {
        assertThat(new CountryLookup("").of(null)).isNull();
    }
}
