package com.tarikusta.spacesurvivors.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

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

    /** Used when the client sends no device id, so local curl/Postman calls still work. */
    private static final String FALLBACK_DEVICE = "dev-unknown";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(DEVICE_HEADER);
        String deviceId = (header == null || header.isBlank()) ? FALLBACK_DEVICE : header.trim();

        request.setAttribute(Caller.ATTR, new Caller(deviceId, clientIp(request)));
        chain.doFilter(request, response);
    }

    /**
     * Behind a proxy or CDN the socket address is the proxy's, and the real client sits
     * at the head of {@code X-Forwarded-For}. Locally there is no proxy, so this returns
     * {@code 127.0.0.1} every time.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
