package com.tarikusta.spacesurvivors.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How hard a single caller may hit the endpoint that costs us a BCrypt hash.
 *
 * <p>A record rather than a class with setters: the values are read once at startup and
 * never change, and constructor binding means an invalid configuration fails while the
 * application is starting instead of on the first request that hits the limit.</p>
 *
 * @param capacity   tokens a caller starts with, which is also the largest burst allowed
 *                   before the refill rate becomes the ceiling
 * @param window     how long a fully drained bucket takes to refill completely, so the
 *                   sustained rate is {@code capacity} requests per {@code window}
 * @param maxClients how many callers are tracked at once — the bound that keeps the
 *                   limiter from becoming a memory-exhaustion lever of its own
 * @param enabled    off switch. Left on everywhere; tests that are not about the limit
 *                   turn it off rather than counting requests.
 */
@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int capacity,
        @DefaultValue("1m") Duration window,
        @DefaultValue("100000") int maxClients) {

    /**
     * Refuse to start on a configuration that cannot work.
     *
     * <p>A capacity of zero would refuse every login attempt in the system, and a window
     * of zero divides by nothing inside the refill calculation. Both are far better as a
     * startup failure than as an outage nobody can explain.</p>
     */
    public RateLimitProperties {
        if (capacity < 1) {
            throw new IllegalArgumentException("app.ratelimit.capacity must be at least 1, was " + capacity);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("app.ratelimit.window must be a positive duration, was " + window);
        }
        if (maxClients < 1) {
            throw new IllegalArgumentException("app.ratelimit.max-clients must be at least 1, was " + maxClients);
        }
    }

    /**
     * How long an idle caller's bucket is kept.
     *
     * <p>Twice the window, and the factor matters. Evicting a bucket hands its owner a
     * full one, so eviction must never happen sooner than the bucket would have refilled
     * on its own — otherwise dropping the entry is indistinguishable from raising the
     * limit. A drained bucket is full again after exactly one window; anyone still
     * knocking keeps their entry alive by touching it.</p>
     */
    Duration retention() {
        return window.multipliedBy(2);
    }
}
