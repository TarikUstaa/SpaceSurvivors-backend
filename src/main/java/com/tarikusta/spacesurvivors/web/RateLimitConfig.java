package com.tarikusta.spacesurvivors.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Wires {@link RateLimitFilter}: where its buckets live, and where in the filter chain it
 * runs.
 *
 * <p>Both of those are decisions with teeth, which is why they are here and explained
 * rather than scattered through the filter itself. What is <em>not</em> decided here is
 * which endpoints are guarded or how large their allowances are — that is
 * {@link RateLimitedEndpoint}, read by this class and by the filter alike.</p>
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    /**
     * Ten places ahead of Spring Security.
     *
     * <p>Spring Boot registers the security chain at
     * {@link SecurityFilterProperties#DEFAULT_FILTER_ORDER} ({@code -100}); a smaller
     * number runs earlier. The constant is referenced rather than the literal so that the
     * intent — <em>before</em> security — is what the code says, and so it stays true if
     * Boot ever moves its own default.</p>
     *
     * <p>This is the line that makes the whole feature work. A refused caller never
     * reaches token parsing, never reaches a controller, and so never reaches the BCrypt
     * comparison the limit exists to protect. Order this filter after the security chain
     * and it still returns 429s, but the expensive work it was meant to prevent has
     * already been queued behind it.</p>
     */
    static final int FILTER_ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10;

    /**
     * One bucket per caller per guarded endpoint, in a cache bounded in both size and time.
     *
     * <p><b>The bound is not housekeeping, it is the same defence again.</b> A plain map
     * keyed by address grows once per distinct caller and never shrinks, so an attacker
     * with a spread of addresses would exhaust memory through the very thing meant to stop
     * them — trading a CPU exhaustion for a slower, more permanent one. Caffeine caps the
     * entry count and drops callers who have gone quiet.</p>
     *
     * <p>Eviction under size pressure can hand a full bucket to someone who had spent
     * theirs, so the cap is set high enough that ordinary traffic never approaches it. It
     * is worth being clear-eyed that this is the trade being made: a bounded limiter can
     * be nudged towards forgetting, an unbounded one can be pushed over. Note also which
     * way the eviction policy leans — Caffeine keeps the entries it sees most, which are
     * precisely the callers being limited hardest.</p>
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitProperties limits, ObjectMapper json) {

        Map<RateLimitedEndpoint, Bandwidth> bandwidths = new EnumMap<>(RateLimitedEndpoint.class);
        for (RateLimitedEndpoint endpoint : RateLimitedEndpoint.values()) {
            bandwidths.put(endpoint, bandwidth(endpoint.capacity(limits), limits.window()));
        }

        Cache<String, Bucket> buckets = Caffeine.newBuilder()
                .maximumSize(limits.maxClients())
                .expireAfterAccess(limits.retention())
                .build();

        // Keyed by endpoint as well as by caller, so each one holds its own bucket —
        // RateLimitFilter explains why neither should be able to spend the other's allowance.
        // It is also what lets them be sized differently, since the bandwidth is chosen when a
        // key's bucket is first built.
        //
        // Caffeine's two-argument get is atomic, so concurrent first requests from one caller
        // share a single bucket instead of each building one and overwriting the others —
        // which would have handed out a fresh allowance per racing thread.
        BiFunction<RateLimitedEndpoint, String, Bucket> store = (endpoint, caller) -> buckets.get(
                endpoint.name() + "|" + caller,
                unused -> Bucket.builder().addLimit(bandwidths.get(endpoint)).build());

        var registration = new FilterRegistrationBean<>(new RateLimitFilter(store, json, limits));
        registration.setOrder(FILTER_ORDER);
        registration.setEnabled(limits.enabled());
        return registration;
    }

    /**
     * Greedy refill: tokens trickle back continuously rather than arriving all at once when
     * the window turns over. An interval refill would let a caller spend a full bucket at the
     * end of one window and another at the start of the next, which is twice the intended rate
     * at exactly the moment it matters.
     */
    private static Bandwidth bandwidth(long capacity, Duration window) {
        return Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, window)
                .build();
    }
}
