package com.tarikusta.spacesurvivors.web;

import java.util.function.ToLongFunction;

/**
 * Every endpoint the rate limiter guards, in one table.
 *
 * <p><b>This type exists because of D26.</b> The limiter was written for one endpoint and
 * explained itself in terms of "the endpoint that costs a BCrypt hash". Months later a second
 * endpoint with exactly that cost was added — the backoffice sign-in — and nothing connected
 * the two, so it went unguarded. The fix at the time added it; what it did not do was make the
 * <em>next</em> such endpoint obvious.</p>
 *
 * <p>The state it left behind said the same thing in two files: two path constants and two
 * capacity fields in {@link RateLimitFilter}, and a ternary on one of those paths in
 * {@link RateLimitConfig} choosing which bandwidth to build. Three edits in two files to add a
 * row, with nothing to fail if one of them was forgotten. Here a guarded endpoint is one line,
 * and both the filter and the configuration read it from the same place.</p>
 *
 * <p>Each row answers the three questions there are to ask about a guarded endpoint: which
 * path, how much of it is allowed, and who is on the other end when it is refused.</p>
 */
enum RateLimitedEndpoint {

    /**
     * The device token endpoint.
     *
     * <p>Matched exactly. Spring MVC does not route {@code /v1/auth/token/} or
     * {@code /V1/Auth/Token} to the handler — trailing-slash matching was removed in Spring 6
     * and paths are case-sensitive — so neither near-miss is a way around the comparison. They
     * reach a 404 instead, which costs nothing.</p>
     */
    DEVICE_TOKEN("/v1/auth/token", Audience.API, RateLimitProperties::capacity),

    /**
     * The backoffice sign-in form.
     *
     * <p>It spends the same BCrypt hash per attempt as the token endpoint, so it was always
     * the same denial-of-service lever. What makes it the worse of the two is what sits behind
     * it: a device secret is 256 bits of randomness and guessing it is not an attack anyone
     * would attempt, while an administrator's password was chosen by a person and guessing it
     * very much is.</p>
     *
     * <p>Note this is Spring Security's {@code loginProcessingUrl} rather than a path any
     * controller of ours serves — the form posts to a filter. That is precisely why the limit
     * has to live out here, ahead of the security chain: there is no controller to put it
     * in.</p>
     */
    ADMIN_LOGIN("/admin/login", Audience.BROWSER, RateLimitProperties::adminCapacity);

    /**
     * Who is on the other end, which is the only thing that differs about being refused.
     *
     * <p>A ProblemDetail document is the right answer to a program and a wall of JSON to a
     * person. Naming the audience rather than the endpoint is what keeps the third guarded
     * path from needing a third refusal method.</p>
     */
    enum Audience { API, BROWSER }

    private final String path;
    private final Audience audience;
    private final ToLongFunction<RateLimitProperties> capacity;

    RateLimitedEndpoint(String path, Audience audience,
                        ToLongFunction<RateLimitProperties> capacity) {
        this.path = path;
        this.audience = audience;
        this.capacity = capacity;
    }

    /** The guarded endpoint at this path, or null when the path is not guarded at all. */
    static RateLimitedEndpoint matching(String path) {
        for (RateLimitedEndpoint endpoint : values()) {
            if (endpoint.path.equals(path)) {
                return endpoint;
            }
        }
        return null;
    }

    String path() {
        return path;
    }

    Audience audience() {
        return audience;
    }

    /**
     * How many requests per window this endpoint allows.
     *
     * <p>A function of the configuration rather than a number, so the row says <em>which
     * setting sizes it</em> and the value still comes from properties at startup.</p>
     */
    long capacity(RateLimitProperties limits) {
        return capacity.applyAsLong(limits);
    }
}
