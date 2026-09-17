package com.tarikusta.spacesurvivors.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Re-checks, on every backoffice request, that the session still describes the account.
 *
 * <p><b>The gap this closes.</b> Spring Security reads {@code admin_user} exactly once — at
 * sign-in — and copies the role into the session. From then on the session is believed. That
 * was harmless with one administrator who never changed; with accounts that can be disabled and
 * demoted it is a hole, and a quiet one: an administrator disables a SUPPORT account, sees the
 * row say "disabled", and the person behind it keeps working until their session happens to
 * expire. A demoted ADMIN keeps ADMIN the same way. The page would say one thing and the
 * application would do another.</p>
 *
 * <p>So the row is read again here, and the session is ended the moment it disagrees:</p>
 * <ul>
 *   <li>the account is gone or disabled → signed out;</li>
 *   <li>the role in the row is not the role in the session → signed out, to sign in again with
 *       the right one. Not silently upgraded or downgraded in place — a person whose permissions
 *       changed should notice that they did;</li>
 *   <li>the account's password changed since this session signed in (the session epoch, V8) →
 *       signed out;</li>
 *   <li>the password was right but the second factor has not been given yet → every page except
 *       the code form redirects to it;</li>
 *   <li>the account still has a password somebody else chose → every page except the password
 *       form redirects to it.</li>
 * </ul>
 *
 * <p><b>Runs before {@code AuthorizationFilter}</b>, and the order is the point: the URL rules
 * decide using the authorities in the session, so a demoted administrator must be caught before
 * those rules see their stale ADMIN, not after.</p>
 *
 * <p><b>One query per page.</b> Deliberately not cached — a cache is exactly the staleness this
 * exists to remove, and a backoffice with a handful of people clicking through it will never
 * notice a primary-key lookup.</p>
 *
 * <p><b>Not a bean.</b> A {@code Filter} declared as a Spring bean is registered by Spring Boot
 * into the servlet container as well, where it would run for every request — including the
 * game's. It is constructed inside the admin chain so that the admin chain is the only place it
 * exists.</p>
 */
final class AdminSessionGuard extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionGuard.class);

    /** The session attribute holding the account's session epoch as of sign-in (V8). */
    static final String EPOCH_ATTRIBUTE = "admin.sessionEpoch";

    /**
     * Set at sign-in for an account with two-factor, removed when the second step passes. While it
     * is set the session is authenticated — the password was right — and is allowed nowhere but the
     * code form.
     */
    static final String TWO_FACTOR_PENDING = "admin.twoFactorPending";

    private static final Set<String> TWO_FACTOR_FLOW = Set.of("/admin/2fa", "/admin/logout");

    /** Reachable while a password change is being forced, or there would be no way to do it. */
    private static final Set<String> PASSWORD_FLOW = Set.of("/admin/password", "/admin/logout");

    private final AdminUserRepository admins;

    AdminSessionGuard(AdminUserRepository admins) {
        this.admins = admins;
    }

    /** The public parts of the backoffice have no session worth checking. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathOf(request);
        return path.equals("/admin/login") || path.startsWith("/admin/assets/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            // Nobody is signed in; the URL rules will send them to the login page.
            chain.doFilter(request, response);
            return;
        }

        AdminUser admin = admins.findByUsernameIgnoreCase(auth.getName()).orElse(null);

        if (admin == null || !admin.isEnabled() || !holds(auth, admin.getRole())
                || epochOf(request) != admin.getSessionEpoch()) {
            log.info("ended the backoffice session of '{}': account {}", auth.getName(),
                    admin == null ? "no longer exists"
                            : !admin.isEnabled() ? "was disabled"
                            : !holds(auth, admin.getRole()) ? "changed role"
                            : "had its password changed");
            endSession(request);
            response.sendRedirect(request.getContextPath() + "/admin/login?revoked");
            return;
        }

        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(TWO_FACTOR_PENDING))
                && !TWO_FACTOR_FLOW.contains(pathOf(request))) {
            response.sendRedirect(request.getContextPath() + "/admin/2fa");
            return;
        }

        if (admin.isMustChangePassword() && !PASSWORD_FLOW.contains(pathOf(request))) {
            response.sendRedirect(request.getContextPath() + "/admin/password?required");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Exactly the role in the row, and nothing more. "Contains" rather than "equals" on the set
     * would let a session that somehow held both authorities pass as either.
     */
    private static boolean holds(Authentication auth, String role) {
        Set<String> granted = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .collect(Collectors.toSet());
        return granted.equals(Set.of("ROLE_" + role));
    }

    /**
     * The epoch this session signed in under. No attribute reads as 0, the column's default — so a
     * session from before V8, or one a test built without signing in, is valid until its account's
     * first password change.
     */
    private static int epochOf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(EPOCH_ATTRIBUTE);
        return value instanceof Integer epoch ? epoch : 0;
    }

    private static void endSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
