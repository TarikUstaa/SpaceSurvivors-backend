package com.tarikusta.spacesurvivors.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter is where hostile input first meets the application, and where two real
 * defects lived: an unparseable address reached a Postgres {@code inet} column and turned
 * every request into a 500, and a forged one was believed and stored.
 */
class DeviceAuthFilterTest {

    private final FilterChain chain = Mockito.mock(FilterChain.class);

    private Caller callerFor(MockHttpServletRequest request, boolean trustForwarded) throws Exception {
        new DeviceAuthFilter(trustForwarded).doFilter(request, new MockHttpServletResponse(), chain);
        return (Caller) request.getAttribute(Caller.ATTR);
    }

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Device dev-abc");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    @DisplayName("the device id is taken from the header and the chain continues")
    void readsTheDeviceId() throws Exception {
        Caller caller = callerFor(request(null), false);

        assertThat(caller.deviceId()).isEqualTo("dev-abc");
        Mockito.verify(chain).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("no device id is refused rather than sharing an anonymous profile")
    void rejectsARequestWithNoDeviceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new DeviceAuthFilter(false).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Authorization");
        assertThat(request.getAttribute(Caller.ATTR)).isNull();
        Mockito.verify(chain, Mockito.never()).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("a header with the wrong scheme is refused")
    void rejectsAnUnknownScheme() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer some.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new DeviceAuthFilter(false).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the scheme is matched case-insensitively, as RFC 7235 requires")
    void acceptsAnyCasingOfTheScheme() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "device dev-abc");

        assertThat(callerFor(request, false).deviceId()).isEqualTo("dev-abc");
    }

    @Test
    void rejectsABlankDeviceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Device    ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new DeviceAuthFilter(false).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("health probes still answer without one")
    void letsProbesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new DeviceAuthFilter(false).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        Mockito.verify(chain).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void capsAnOverlongDeviceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Device " + "x".repeat(500));

        assertThat(callerFor(request, false).deviceId()).hasSize(64);
    }

    @ParameterizedTest
    @DisplayName("a value that is not an address never leaves the filter")
    @ValueSource(strings = {
            "not-an-ip",
            "999.999.999.999",
            ":::::",
            "'; DROP TABLE player_profile; --",
            "example.com",
            "1.2.3",
    })
    void discardsAnythingUnparseable(String forwarded) throws Exception {
        // Even with the header trusted, junk must not be passed on: downstream it reaches
        // an inet column and would fail the whole statement. Falling back to the socket
        // address is better than nothing — that at least is a fact.
        Caller caller = callerFor(request(forwarded), true);

        assertThat(caller.ip()).isNotEqualTo(forwarded);
        assertThat(caller.ip()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("null only when even the socket address is unusable")
    void yieldsNullWhenNothingIsKnown() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("unknown");
        request.addHeader("Authorization", "Device dev-abc");

        assertThat(callerFor(request, false).ip()).isNull();
    }

    @Test
    @DisplayName("a forged address is ignored unless a proxy is trusted")
    void doesNotBelieveTheHeaderByDefault() throws Exception {
        Caller caller = callerFor(request("8.8.8.8"), false);

        assertThat(caller.ip()).isEqualTo("127.0.0.1");
    }

    @Test
    void believesTheHeaderOnlyWhenConfiguredTo() throws Exception {
        assertThat(callerFor(request("8.8.8.8"), true).ip()).isEqualTo("8.8.8.8");
    }

    @Test
    @DisplayName("the original client is the first entry of the proxy chain")
    void takesTheFirstAddressFromTheChain() throws Exception {
        assertThat(callerFor(request("8.8.8.8, 10.0.0.1, 10.0.0.2"), true).ip()).isEqualTo("8.8.8.8");
    }

    @Test
    void stripsAnIpv6ZoneWhichPostgresWouldReject() throws Exception {
        assertThat(callerFor(request("fe80::1%eth0"), true).ip()).doesNotContain("%");
    }
}
