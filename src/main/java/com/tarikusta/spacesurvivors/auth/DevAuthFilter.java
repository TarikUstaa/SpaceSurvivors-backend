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
 * DEV ONLY — no real authentication yet.
 *
 * <p>A servlet filter runs on every request <em>before</em> it reaches a controller.
 * This one reads the caller's id from the {@code X-Dev-User} header (default
 * {@code dev-user}) and stores it on the request as the {@code userId} attribute.
 * Controllers then pick it up with {@code @RequestAttribute("userId")}.
 *
 * <p>When real auth arrives (token verification), only this class changes — it sets
 * the same {@code userId} attribute. Every controller stays untouched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DevAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("X-Dev-User");
        String userId = (header == null || header.isBlank()) ? "dev-user" : header.trim();
        request.setAttribute(USER_ID, userId);
        chain.doFilter(request, response);
    }
}
