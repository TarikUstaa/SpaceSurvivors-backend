package com.tarikusta.spacesurvivors.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ceiling on {@code POST /v1/auth/token}.
 *
 * <p>That endpoint spends about 100 ms of CPU on BCrypt per call, whether or not the
 * secret is correct, so an unlimited one is a denial-of-service lever available to anyone
 * with a socket. These tests pin the three properties that make the limit worth having:
 * honest bursts still get through, a flood is refused before it costs anything, and one
 * caller's behaviour never spends another caller's allowance.</p>
 *
 * <p>The filter is built through {@link RateLimitConfig} rather than by hand, so the
 * bandwidth and the bucket store under test are the ones the application actually runs.</p>
 */
class RateLimitFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static FilterRegistrationBean<RateLimitFilter> registration(int capacity) {
        return new RateLimitConfig().rateLimitFilterRegistration(
                new RateLimitProperties(true, capacity, Duration.ofMinutes(1), 1_000, capacity), JSON);
    }

    private static RateLimitFilter filterAllowing(int capacity) {
        return registration(capacity).getFilter();
    }

    /** One POST to the guarded path from the given address. */
    private static MockHttpServletResponse post(RateLimitFilter filter, String address) throws Exception {
        return send(filter, "POST", RateLimitFilter.GUARDED_PATH, address);
    }

    private static MockHttpServletResponse send(RateLimitFilter filter, String method, String path, String address)
            throws Exception {

        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(address);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    /** Whether the request was handed on rather than refused here. */
    private static boolean passedThrough(MockHttpServletResponse response) {
        return response.getStatus() != HttpStatus.TOO_MANY_REQUESTS.value();
    }

    @Nested
    @DisplayName("spending the bucket")
    class SpendingTheBucket {

        @Test
        @DisplayName("a burst up to the capacity is handed on untouched")
        void burstWithinCapacityPasses() throws Exception {
            RateLimitFilter filter = filterAllowing(3);

            for (int attempt = 1; attempt <= 3; attempt++) {
                assertThat(passedThrough(post(filter, "10.0.0.1")))
                        .as("attempt %d of 3 should still be allowed", attempt)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the request past the capacity is refused")
        void requestPastCapacityIsRefused() throws Exception {
            RateLimitFilter filter = filterAllowing(3);
            for (int i = 0; i < 3; i++) {
                post(filter, "10.0.0.1");
            }

            assertThat(post(filter, "10.0.0.1").getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }

        @Test
        @DisplayName("the remaining allowance is advertised as it is spent")
        void remainingIsAdvertised() throws Exception {
            RateLimitFilter filter = filterAllowing(3);

            assertThat(post(filter, "10.0.0.1").getHeader("RateLimit-Remaining")).isEqualTo("2");
            assertThat(post(filter, "10.0.0.1").getHeader("RateLimit-Remaining")).isEqualTo("1");
            assertThat(post(filter, "10.0.0.1").getHeader("RateLimit-Remaining")).isEqualTo("0");
        }

        @Test
        @DisplayName("the ceiling is advertised too")
        void limitIsAdvertised() throws Exception {
            assertThat(post(filterAllowing(7), "10.0.0.1").getHeader("RateLimit-Limit")).isEqualTo("7");
        }
    }

    @Nested
    @DisplayName("being refused")
    class BeingRefused {

        private MockHttpServletResponse refusal() throws Exception {
            RateLimitFilter filter = filterAllowing(1);
            post(filter, "10.0.0.1");
            return post(filter, "10.0.0.1");
        }

        @Test
        @DisplayName("says when to come back, in whole seconds, never zero")
        void carriesRetryAfter() throws Exception {
            String retryAfter = refusal().getHeader(HttpHeaders.RETRY_AFTER);

            // Rounded up: advertising a moment that is still too early would send a
            // well-behaved client straight into a second refusal.
            assertThat(retryAfter).isNotNull();
            assertThat(Long.parseLong(retryAfter)).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("answers with the same problem+json shape as every other error")
        void carriesProblemDetailBody() throws Exception {
            MockHttpServletResponse response = refusal();

            assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

            JsonNode body = JSON.readTree(response.getContentAsString());
            assertThat(body.get("status").asInt()).isEqualTo(429);
            assertThat(body.get("detail").asString()).isNotBlank();
        }

        @Test
        @DisplayName("never reaches the rest of the chain")
        void doesNotCallTheChain() throws Exception {
            RateLimitFilter filter = filterAllowing(1);
            post(filter, "10.0.0.1");

            MockHttpServletRequest request = new MockHttpServletRequest("POST", RateLimitFilter.GUARDED_PATH);
            request.setRemoteAddr("10.0.0.1");
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);

            // The whole point: BCrypt lives downstream, so a refused caller must stop here.
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Nested
    @DisplayName("telling callers apart")
    class TellingCallersApart {

        @Test
        @DisplayName("one caller exhausting the bucket does not refuse another")
        void bucketsArePerCaller() throws Exception {
            RateLimitFilter filter = filterAllowing(2);
            post(filter, "10.0.0.1");
            post(filter, "10.0.0.1");
            assertThat(passedThrough(post(filter, "10.0.0.1"))).isFalse();

            assertThat(passedThrough(post(filter, "10.0.0.2")))
                    .as("a second address must have its own allowance")
                    .isTrue();
        }

        @Test
        @DisplayName("a caller with no address is still limited rather than exempt")
        void missingAddressSharesOneBucket() throws Exception {
            RateLimitFilter filter = filterAllowing(1);

            // Erring towards limiting too much is the right way to be wrong about identity;
            // treating "no address" as "not covered" would be an opt-out anyone could take.
            assertThat(passedThrough(post(filter, null))).isTrue();
            assertThat(passedThrough(post(filter, null))).isFalse();
        }
    }

    @Nested
    @DisplayName("what is guarded")
    class WhatIsGuarded {

        @Test
        @DisplayName("only the token endpoint spends tokens")
        void otherPathsAreUntouched() throws Exception {
            RateLimitFilter filter = filterAllowing(1);
            post(filter, "10.0.0.1");   // bucket now empty for this address

            assertThat(passedThrough(send(filter, "POST", "/v1/progress", "10.0.0.1")))
                    .as("the save endpoint is cheap and must not share the auth ceiling")
                    .isTrue();
        }

        @Test
        @DisplayName("only POST spends tokens")
        void otherMethodsAreUntouched() throws Exception {
            RateLimitFilter filter = filterAllowing(1);
            post(filter, "10.0.0.1");

            assertThat(passedThrough(send(filter, "GET", RateLimitFilter.GUARDED_PATH, "10.0.0.1"))).isTrue();
        }
    }

    @Nested
    @DisplayName("wiring")
    class Wiring {

        @Test
        @DisplayName("runs ahead of the security chain, or it protects nothing")
        void orderedBeforeSpringSecurity() {
            // If this ever slips past Spring Security's own order, a refused request has
            // already paid for token parsing and is one controller away from BCrypt — the
            // filter would still answer 429 while no longer preventing anything.
            assertThat(RateLimitConfig.FILTER_ORDER)
                    .isLessThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
            assertThat(registration(10).getOrder()).isEqualTo(RateLimitConfig.FILTER_ORDER);
        }

        @Test
        @DisplayName("the off switch actually unregisters the filter")
        void disabledMeansNotRegistered() {
            FilterRegistrationBean<RateLimitFilter> off = new RateLimitConfig().rateLimitFilterRegistration(
                    new RateLimitProperties(false, 10, Duration.ofMinutes(1), 1_000, 10), JSON);

            assertThat(off.isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("configuration that cannot work")
    class ImpossibleConfiguration {

        @Test
        @DisplayName("a capacity of zero is refused at startup, not at the first login")
        void zeroCapacityIsRejected() {
            // Left unchecked this refuses every login in the system, and does it silently.
            assertThatThrownBy(() -> new RateLimitProperties(true, 0, Duration.ofMinutes(1), 1_000, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity");
        }

        @Test
        @DisplayName("a window of zero is refused at startup")
        void zeroWindowIsRejected() {
            assertThatThrownBy(() -> new RateLimitProperties(true, 10, Duration.ZERO, 1_000, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("window");
        }

        @Test
        @DisplayName("buckets are kept at least as long as they take to refill")
        void retentionOutlastsRefill() {
            // Evicting sooner than a bucket refills would make forgetting a caller
            // indistinguishable from raising their limit.
            RateLimitProperties limits = new RateLimitProperties(true, 10, Duration.ofMinutes(1), 1_000, 10);

            assertThat(limits.retention()).isGreaterThanOrEqualTo(limits.window());
        }
    }

    @Nested
    @DisplayName("the backoffice sign-in form")
    class AdminLogin {

        /** A filter whose two guarded paths have deliberately different allowances. */
        private RateLimitFilter filterAllowing(int deviceCapacity, int adminCapacity) {
            return new RateLimitConfig().rateLimitFilterRegistration(
                    new RateLimitProperties(true, deviceCapacity, Duration.ofMinutes(1),
                            1_000, adminCapacity), JSON).getFilter();
        }

        private MockHttpServletResponse login(RateLimitFilter filter, String address) throws Exception {
            return send(filter, "POST", RateLimitFilter.GUARDED_ADMIN_PATH, address);
        }

        @Test
        @DisplayName("is limited at all — it spends the same BCrypt hash as the token endpoint")
        void isLimited() throws Exception {
            RateLimitFilter filter = filterAllowing(10, 2);

            assertThat(login(filter, "10.0.0.1").getStatus()).isEqualTo(200);
            assertThat(login(filter, "10.0.0.1").getStatus()).isEqualTo(200);

            // The third is refused. Before this existed there was no third — or thousandth,
            // against a password a person chose.
            MockHttpServletResponse refused = login(filter, "10.0.0.1");
            assertThat(refused.getStatus()).isEqualTo(HttpStatus.FOUND.value());
            assertThat(refused.getRedirectedUrl()).endsWith("/admin/login?throttled");
            assertThat(refused.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        }

        @Test
        @DisplayName("is refused as a redirect, not as a JSON document")
        void refusesWithAPage() throws Exception {
            RateLimitFilter filter = filterAllowing(10, 1);
            login(filter, "10.0.0.2");

            MockHttpServletResponse refused = login(filter, "10.0.0.2");

            // Whoever hit this limit is holding a browser. A ProblemDetail body is the right
            // answer to a program and a wall of JSON to a person.
            assertThat(refused.getContentAsString()).doesNotContain("problem");
            assertThat(refused.getRedirectedUrl()).contains("throttled");
        }

        @Test
        @DisplayName("holds its own bucket, so neither endpoint can spend the other's allowance")
        void doesNotShareABucketWithTheTokenEndpoint() throws Exception {
            RateLimitFilter filter = filterAllowing(2, 2);

            // Drain the device endpoint completely from this address.
            post(filter, "10.0.0.3");
            post(filter, "10.0.0.3");
            assertThat(passedThrough(post(filter, "10.0.0.3"))).isFalse();

            // The administrator behind that same address — the same office, the same router —
            // can still sign in. One shared bucket would turn an attack on either endpoint
            // into an outage on the other.
            assertThat(login(filter, "10.0.0.3").getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a GET of the login page spends nothing")
        void doesNotCountPageLoads() throws Exception {
            RateLimitFilter filter = filterAllowing(10, 1);

            // Only the POST costs a BCrypt hash. Counting the GET would mean the page stopped
            // rendering after one look at it.
            send(filter, "GET", RateLimitFilter.GUARDED_ADMIN_PATH, "10.0.0.4");
            send(filter, "GET", RateLimitFilter.GUARDED_ADMIN_PATH, "10.0.0.4");

            assertThat(login(filter, "10.0.0.4").getStatus()).isEqualTo(200);
        }
    }
}
