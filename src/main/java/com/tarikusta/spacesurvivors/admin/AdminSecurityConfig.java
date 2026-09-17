package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.auth.ClientAddress;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;
import java.time.Instant;

/**
 * Who may reach the backoffice, and how they prove it.
 *
 * <p><b>A second filter chain, not a second rule in the first one.</b> Spring Security
 * consults chains in order and the first whose matcher accepts the request handles it
 * alone. This chain claims {@code /admin/**}; {@link
 * com.tarikusta.spacesurvivors.auth.SecurityConfig} takes everything else. Nothing about
 * one chain leaks into the other, which is exactly what is wanted here, because the two
 * halves of this application disagree about almost every security question:</p>
 *
 * <table>
 *   <tr><th></th><th>{@code /v1/**} (the game)</th><th>{@code /admin/**} (a person)</th></tr>
 *   <tr><td>Credential</td><td>signed token on every request</td><td>password, once</td></tr>
 *   <tr><td>State</td><td>none — stateless</td><td>a session cookie</td></tr>
 *   <tr><td>CSRF</td><td>disabled, and safe to</td><td>enabled, and must be</td></tr>
 *   <tr><td>Rejection</td><td>401, for a program</td><td>a login page, for a person</td></tr>
 * </table>
 *
 * <p><b>Why CSRF protection comes back here.</b> The API can switch it off because a token
 * has to be attached deliberately by the client — a browser will not add an
 * {@code Authorization} header to a request some other site triggered, so there is nothing
 * for a forged request to ride on. A session cookie is the opposite: the browser attaches
 * it to every request to this origin, including one that a page on another site caused. The
 * CSRF token is what tells a request the administrator meant to send from one they were
 * tricked into sending. Turning it off here would mean any page they visit while signed in
 * could act as them.</p>
 *
 * <h2>Two roles, checked twice</h2>
 *
 * <p>The URL rules below are the first check: they are what stops a SUPPORT account from even
 * reaching the users page. {@code @EnableMethodSecurity} switches on the second — the
 * {@code @PreAuthorize} on the service methods that do the damage ({@code AdminPlayerService.delete},
 * everything in {@code AdminUserService}). Two checks for one rule is deliberate, and it is D29's
 * argument again: <b>a rule attached to a URL only protects requests shaped like that URL.</b>
 * A second screen that calls the same service, or a URL pattern that stops matching after a
 * rename, would walk straight past the first check. The annotation travels with the operation.</p>
 *
 * <p>{@code @EnableMethodSecurity} is application-wide, which sounds larger than it is: it only
 * acts on methods that carry an annotation, and the only ones that do are in this package.</p>
 */
@Configuration
@EnableMethodSecurity
public class AdminSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityConfig.class);

    /**
     * Ahead of the API chain, because the API chain matches every request. Order decides
     * which chain gets first refusal, and a chain with no matcher must be last or it
     * answers for everything.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain admin(HttpSecurity http,
                                     AdminLoginRecorder loginRecorder,
                                     AdminLoginFailureRecorder failureRecorder,
                                     AdminUserRepository admins) throws Exception {
        return http
                .securityMatcher("/admin/**")
                .authorizeHttpRequests(auth -> auth
                        // The login page and the stylesheet it needs, or signing in would
                        // require being signed in.
                        .requestMatchers("/admin/login", "/admin/assets/**").permitAll()
                        // ADMIN only: who may sign in, what everybody did, and the one action
                        // with no undo. "/**" also matches the bare path, so /admin/users itself
                        // is covered and not only what lies beneath it.
                        .requestMatchers("/admin/users/**", "/admin/audit/**",
                                "/admin/test-players/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/admin/players/*/delete").hasRole("ADMIN")
                        // Invented players and scores are public the moment they are written.
                        .requestMatchers(HttpMethod.POST,
                                "/admin/players/new", "/admin/players/*/score").hasRole("ADMIN")
                        // Everything else is the day-to-day work, and both roles do it. Listed
                        // by name rather than as authenticated(): an account whose role this
                        // application does not know gets nothing, not everything.
                        .anyRequest().hasAnyRole("ADMIN", "SUPPORT"))
                // Before the URL rules read the session's role, not after — see the class.
                .addFilterBefore(new AdminSessionGuard(admins), AuthorizationFilter.class)
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .successHandler(loginRecorder)
                        // One message for every kind of failure. "No such user" and "wrong
                        // password" are the same sentence on purpose — the handler redirects
                        // to /admin/login?error exactly as failureUrl() did, and additionally
                        // writes the attempt down.
                        .failureHandler(failureRecorder)
                        .permitAll())
                .logout(out -> out
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login?logout")
                        .deleteCookies("JSESSIONID"))
                // What a stale CSRF token looks like to the person who hit it. See
                // StaleFormHandler — without this it is a bare 401 from a different chain.
                .exceptionHandling(e -> e.accessDeniedHandler(new StaleFormHandler()))
                // Sessions are wanted here, but the id must change the moment the person
                // signs in: otherwise a session id an attacker planted before the login is
                // still valid after it, and it is now an authenticated one.
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .build();
    }

    /**
     * Stamps {@code last_login_at} on the way through, then hands the redirect back to the
     * standard handler.
     *
     * <p>It belongs here rather than in {@link AdminUserDetailsService} because that runs
     * <em>before</em> the password is checked — recording a login there would record every
     * attempt, including the failed ones, which makes the column say the opposite of what
     * it claims.</p>
     */
    @Bean
    public AdminLoginRecorder adminLoginRecorder(AdminUserRepository admins, AdminAudit audit) {
        return new AdminLoginRecorder(admins, audit);
    }

    @Bean
    public AdminLoginFailureRecorder adminLoginFailureRecorder(AdminAudit audit) {
        return new AdminLoginFailureRecorder(audit);
    }

    /**
     * Answers a rejected form in a way the person in front of it can act on.
     *
     * <p><b>The problem.</b> {@code CsrfFilter} sits <em>ahead</em> of
     * {@code ExceptionTranslationFilter} in the chain, so the exception it throws is never
     * translated: it leaves the security chain entirely, the container dispatches to
     * {@code /error}, and {@code /error} is not {@code /admin/**} — so the API chain answers
     * it, with a 401 and no explanation. An administrator whose login page had been open long
     * enough for its token to expire pressed a button and got a blank page with the wrong
     * status on it.</p>
     *
     * <p>Setting an access-denied handler here fixes it because {@code CsrfConfigurer} picks
     * up whatever handler {@code exceptionHandling} registered and gives it to the filter.
     * A stale token then means what it actually means — sign in again — and the request is
     * still refused, which was never in question.</p>
     *
     * <p><b>A refusal that is not about CSRF goes to its own page, not the login page.</b> A
     * signed-in SUPPORT account that reaches an ADMIN page has not gone stale — they are simply
     * not allowed, and a login page would invite them to try the same credentials again.</p>
     *
     * <p>It used to be the default handler's bare 403 here, which was fine while no signed-in
     * person could ever be refused. Now one can, and the default is the same trap as the CSRF
     * case: {@code sendError(403)} triggers the container's error dispatch to {@code /error},
     * which the API chain answers — in the deployed service a SUPPORT user typing
     * {@code /admin/audit} would have met a blank 401. {@code /admin/forbidden} is an ordinary
     * page that sets 403 itself, so no error dispatch happens at all.</p>
     *
     * <p>Worth knowing that MockMvc does not perform the error dispatch, so in tests the
     * original behaviour appeared as a clean 403 and only the deployed service showed the 401.
     * The tests assert the redirects, which both environments agree on.</p>
     */
    static class StaleFormHandler implements AccessDeniedHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException denied) throws IOException, ServletException {
            if (denied instanceof CsrfException) {
                response.sendRedirect(request.getContextPath() + "/admin/login?expired");
                return;
            }
            response.sendRedirect(request.getContextPath() + "/admin/forbidden");
        }
    }

    static class AdminLoginRecorder extends SavedRequestAwareAuthenticationSuccessHandler {

        private final AdminUserRepository admins;
        private final AdminAudit audit;

        AdminLoginRecorder(AdminUserRepository admins, AdminAudit audit) {
            this.admins = admins;
            this.audit = audit;
            setDefaultTargetUrl("/admin/overview");
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request,
                                            HttpServletResponse response,
                                            Authentication authentication)
                throws IOException, ServletException {
            AdminUser admin = admins.findByUsernameIgnoreCase(authentication.getName()).orElse(null);
            if (admin != null) {
                // The session remembers which epoch it signed in under; AdminSessionGuard ends it
                // once the account's epoch moves on (V8).
                request.getSession().setAttribute(AdminSessionGuard.EPOCH_ATTRIBUTE,
                        admin.getSessionEpoch());

                if (admin.hasTwoFactor()) {
                    // Half signed in: the password was right. AdminSessionGuard now allows this
                    // session nowhere but the code form, and the sign-in is recorded — and
                    // last_login_at stamped — only when the second step passes.
                    request.getSession().setAttribute(AdminSessionGuard.TWO_FACTOR_PENDING, true);
                    response.sendRedirect(request.getContextPath() + "/admin/2fa");
                    return;
                }

                admin.setLastLoginAt(Instant.now());
                // Saved explicitly: this runs outside any transaction of ours, so there is no
                // persistence context that would flush the change on its own. The repository's
                // own save() supplies the transaction.
                admins.save(admin);
            }

            // last_login_at holds the most recent sign-in; the audit table holds all of them.
            // Both, because one answers "is this account still in use" at a glance and the
            // other answers "when exactly, and from where", and a single column cannot do both.
            audit.signedIn(authentication.getName(), ClientAddress.of(request));

            log.info("admin '{}' signed in", authentication.getName());
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }

    /**
     * Writes down a refused sign-in, then answers it exactly as before.
     *
     * <p>The superclass with a default failure URL <em>is</em> what {@code failureUrl(...)}
     * builds internally, so swapping one for the other changes what is recorded and nothing
     * about what the browser sees — still one message for every kind of failure.</p>
     *
     * <p>The username comes from the request parameter because the exception does not carry
     * it: {@code BadCredentialsException} deliberately drops the credentials, and for an
     * unknown account there was never a principal to name. It is untrusted input and is
     * treated as such — clipped by {@link AdminAudit}, stored, never interpreted.</p>
     *
     * <p>Only attempts that reached authentication appear here. {@code RateLimitFilter} turns
     * away the eleventh POST to this path before any of this runs, so a sustained brute force
     * shows up as a burst of rows and then silence, rather than as an unbounded table.</p>
     */
    static class AdminLoginFailureRecorder extends SimpleUrlAuthenticationFailureHandler {

        private final AdminAudit audit;

        AdminLoginFailureRecorder(AdminAudit audit) {
            super("/admin/login?error");
            this.audit = audit;
        }

        @Override
        public void onAuthenticationFailure(HttpServletRequest request,
                                            HttpServletResponse response,
                                            AuthenticationException failed)
                throws IOException, ServletException {
            String attempted = request.getParameter("username");

            audit.signInFailed(attempted, ClientAddress.of(request));
            // At warn, not info: a handful of these is somebody mistyping, and a stream of
            // them is the only warning this application gets before an account is guessed.
            log.warn("admin sign-in refused for '{}'", attempted);

            super.onAuthenticationFailure(request, response, failed);
        }
    }
}
