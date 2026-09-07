package com.tarikusta.spacesurvivors.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Works out who is calling, before the request reaches any controller.
 *
 * <p><b>This identifies, it does not authenticate.</b> Whatever device id the caller
 * sends is believed — there is nothing here for the server to verify. Real auth means
 * a token the server can check, and when that lands it replaces the body of this one
 * class: every controller already takes a {@link Caller} and does not care where it
 * came from.</p>
 *
 * <p>Deliberately touches no database. A filter runs on every request including
 * {@code /health}, so it stays cheap; deciding whether a player row should exist is a
 * business rule and lives in the service layer.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DeviceAuthFilter extends OncePerRequestFilter {

    private static final String DEVICE_HEADER = "X-Device-Id";
    private static final String FORWARDED_HEADER = "X-Forwarded-For";

    /** Used when the client sends no device id, so local curl/Postman calls still work. */
    private static final String FALLBACK_DEVICE = "dev-unknown";

    /** Long enough for any id we generate; short enough that a hostile header is capped. */
    private static final int MAX_DEVICE_ID_LENGTH = 64;

    /** Longest possible textual IPv6 address. */
    private static final int MAX_IP_LENGTH = 45;

    /** Four decimal groups. Anything else is either IPv6 or not an address at all. */
    private static final Pattern DOTTED_QUAD = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * Whether {@code X-Forwarded-For} may be believed.
     *
     * <p>That header is only meaningful when a proxy we control added it. Sent straight
     * to an exposed server it is simply a claim, so trusting it unconditionally would
     * let any caller write any address into {@code last_ip}. Off by default; turn it on
     * only once something trustworthy sits in front.</p>
     */
    private final boolean trustForwardedFor;

    public DeviceAuthFilter(@Value("${app.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        request.setAttribute(Caller.ATTR, new Caller(deviceId(request), clientIp(request)));
        chain.doFilter(request, response);
    }

    private static String deviceId(HttpServletRequest request) {
        String header = request.getHeader(DEVICE_HEADER);
        if (header == null || header.isBlank()) {
            return FALLBACK_DEVICE;
        }
        String trimmed = header.trim();
        return trimmed.length() <= MAX_DEVICE_ID_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_DEVICE_ID_LENGTH);
    }

    /**
     * The caller's address, or null when it cannot be established.
     *
     * <p>Always validated before it leaves this method: the value can reach a Postgres
     * {@code inet} column, and an unparseable one there fails the whole statement. Since
     * a header is entirely under the client's control, an unchecked value would let
     * anyone turn any request into a 500.</p>
     */
    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader(FORWARDED_HEADER);
            if (forwarded != null && !forwarded.isBlank()) {
                // the original client is first; the rest of the chain is the proxies
                int comma = forwarded.indexOf(',');
                String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
                String parsed = ipLiteralOrNull(first);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return ipLiteralOrNull(request.getRemoteAddr());
    }

    /**
     * Parse a literal IPv4/IPv6 address, or return null.
     *
     * <p>Never resolves a hostname: a value containing {@code :} cannot be one, and
     * anything else must match four decimal groups first, so
     * {@link InetAddress#getByName} only ever sees a literal and cannot trigger DNS.</p>
     */
    private static String ipLiteralOrNull(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_IP_LENGTH) {
            return null;
        }
        String candidate = value.trim();

        // fe80::1%eth0 — Postgres' inet type does not accept the zone suffix
        int zone = candidate.indexOf('%');
        if (zone >= 0) {
            candidate = candidate.substring(0, zone);
        }

        boolean literal = candidate.indexOf(':') >= 0 || DOTTED_QUAD.matcher(candidate).matches();
        if (!literal) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
