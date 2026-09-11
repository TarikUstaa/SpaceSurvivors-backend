package com.tarikusta.spacesurvivors.web;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.function.BiFunction;

/**
 * Caps how often one caller may hit an endpoint that costs a BCrypt hash.
 *
 * <p><b>The problem this exists for.</b> Verifying a password or a device secret with BCrypt
 * is deliberately slow — roughly 100 ms of CPU, on purpose, so that a stolen table of hashes
 * is impractical to attack offline. That same slowness is a lever: a few hundred requests a
 * second, with wrong credentials, is enough CPU to starve every real player, and the attacker
 * needs no account and no valid credential to do it. The work happens whether or not the
 * credential checks out, so refusing early is the only defence.</p>
 *
 * <p><b>Which endpoints, and how much:</b> {@link RateLimitedEndpoint}, which is the only
 * place that knows. This class asks it and does not decide.</p>
 *
 * <p><b>Why this is a filter, and why it is ordered ahead of Spring Security.</b> The cost is
 * paid inside the controller — or, for the sign-in form, inside the security chain itself — so
 * anything that stops the request earlier will do. Sitting in front means a refused caller
 * costs a map lookup and nothing else: no token parsing, no context setup, and certainly no
 * BCrypt. {@link RateLimitConfig} owns that ordering, and it is the part to be careful with:
 * move this filter after the security chain and those endpoints are expensive to reach
 * again.</p>
 *
 * <p><b>The token bucket.</b> Each caller holds a bucket of tokens that refills steadily over
 * {@link RateLimitProperties#window()}. A request spends one. Bursts up to the capacity go
 * through untouched — a player whose app retries a few times is not a threat — while the
 * sustained rate settles at capacity-per-window. That shape is the point: a flat "one request
 * per six seconds" would break honest clients without slowing an attacker down any further.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final BiFunction<RateLimitedEndpoint, String, Bucket> buckets;
    private final ObjectMapper json;
    private final RateLimitProperties limits;

    RateLimitFilter(BiFunction<RateLimitedEndpoint, String, Bucket> buckets, ObjectMapper json,
                    RateLimitProperties limits) {
        this.buckets = buckets;
        this.json = json;
        this.limits = limits;
    }

    /**
     * Everything except a guarded endpoint passes without touching a bucket.
     *
     * <p>The filter is registered for all paths rather than for URL patterns so that the paths
     * it guards are written down in exactly one place. A short walk over two enum constants
     * per request is not worth splitting that across two files to avoid.</p>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        return RateLimitedEndpoint.matching(pathWithinApplication(request)) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        RateLimitedEndpoint endpoint =
                RateLimitedEndpoint.matching(pathWithinApplication(request));

        // Keyed by endpoint as well as caller, so the limits are genuinely separate. Sharing
        // one bucket would mean an attacker pounding the login form could also lock every
        // player behind that address out of the game — and, the other way round, that a busy
        // mobile carrier's address could spend the allowance an administrator needs to sign
        // in. Neither endpoint should be able to close the other.
        ConsumptionProbe probe = buckets.apply(endpoint, callerKey(request))
                .tryConsumeAndReturnRemaining(1);

        // Headers go on before anything downstream can commit the response. The names carry
        // no "X-" prefix on purpose: RFC 6648 retired that convention in 2012, and these are
        // the names the IETF rate-limit draft settled on.
        response.setHeader("RateLimit-Limit", Long.toString(endpoint.capacity(limits)));
        response.setHeader("RateLimit-Remaining",
                Long.toString(Math.max(0, probe.getRemainingTokens())));

        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfter = secondsUntilRefill(probe);

        // Debug, not warn. Being refused here is this filter working, and under the attack it
        // defends against these arrive by the thousand — logging each one at warn would turn a
        // handled event into the log flood that D15 was about. The counter that belongs at warn
        // level is "how many", which is a metrics job rather than a logging one.
        log.debug("rate limit hit for {} on {}, retry after {}s",
                callerKey(request), endpoint.path(), retryAfter);

        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        switch (endpoint.audience()) {
            case API -> refuseAsProblemDetail(response);
            case BROWSER -> refuseAsRedirect(request, response, endpoint);
        }
    }

    /**
     * 429, with the ProblemDetail body every other error in this service uses.
     *
     * <p>Written straight to the response because this runs ahead of the dispatcher: no
     * controller has been chosen, so {@code @ExceptionHandler} would never see an exception
     * thrown here. Matching {@link ApiExceptionHandler}'s shape by hand is what keeps clients
     * parsing one error format instead of two.</p>
     */
    private void refuseAsProblemDetail(HttpServletResponse response) throws IOException {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "too many requests, slow down");

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
        response.flushBuffer();
    }

    /**
     * The same refusal, for someone holding a browser rather than writing a client.
     *
     * <p>A wall of JSON is no use to a person, so the form is sent back to itself with a marker
     * the page turns into a sentence. That makes the status a redirect rather than 429;
     * {@code Retry-After} still carries the wait, which RFC 9110 allows on a 3xx precisely for
     * this — "wait this long before following the redirect".</p>
     *
     * <p>A redirect rather than a rendered page because this filter runs before the dispatcher,
     * so there is no view resolver here, and sending the browser back is the one thing that
     * needs no machinery at all.</p>
     */
    private void refuseAsRedirect(HttpServletRequest request, HttpServletResponse response,
                                  RateLimitedEndpoint endpoint) throws IOException {
        response.sendRedirect(request.getContextPath() + endpoint.path() + "?throttled");
    }

    /**
     * {@code Retry-After} in whole seconds, rounded up and never below one.
     *
     * <p>RFC 9110 defines the value as whole seconds. Rounding down would advertise a moment
     * that is still too early, so a client following our own advice would be refused again;
     * rounding up costs a fraction of a second and makes the answer honest.</p>
     */
    private static long secondsUntilRefill(ConsumptionProbe probe) {
        long nanos = probe.getNanosToWaitForRefill();
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }

    /**
     * Who is being limited: the address the connection actually came from.
     *
     * <p><b>{@code X-Forwarded-For} is deliberately not consulted</b>, and here the reason is
     * sharper than it was for {@code last_ip} in D14. There, believing the header let a caller
     * write a false address into a column. Here it would let them erase the limit altogether —
     * a new value in that header is a new bucket, so an attacker would simply count upwards and
     * never run out. A header a client controls cannot be the thing that decides how much of
     * our CPU that client gets. Behind a proxy we run, the supported answer is
     * {@code server.forward-headers-strategy=framework}, which rewrites {@code getRemoteAddr}
     * itself and leaves this code correct without changing it.</p>
     */
    private static String callerKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        // A missing address means everyone in that situation shares one bucket. That errs
        // towards limiting too much rather than too little, which is the right way to be
        // wrong about who a caller is.
        return address == null || address.isBlank() ? "unknown" : address;
    }

    /** The request path with any context path removed, so the table's paths stay literal. */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }
}
