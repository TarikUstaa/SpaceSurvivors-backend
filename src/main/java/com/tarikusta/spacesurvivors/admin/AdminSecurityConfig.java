package com.tarikusta.spacesurvivors.admin;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
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
 *   <tr><td>Rejection</td><td>401, for a program to read</td><td>a login page, for a person</td></tr>
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
 */
@Configuration
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
                                     AdminLoginRecorder loginRecorder) throws Exception {
        return http
                .securityMatcher("/admin/**")
                .authorizeHttpRequests(auth -> auth
                        // The login page and the stylesheet it needs, or signing in would
                        // require being signed in.
                        .requestMatchers("/admin/login", "/admin/assets/**").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .successHandler(loginRecorder)
                        // One message for every kind of failure. "No such user" and "wrong
                        // password" are the same sentence on purpose.
                        .failureUrl("/admin/login?error")
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
    public AdminLoginRecorder adminLoginRecorder(AdminUserRepository admins) {
        return new AdminLoginRecorder(admins);
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
     * <p><b>Only CSRF failures are redirected.</b> Everything else keeps the default 403: a
     * signed-in person without the ADMIN role has not gone stale, they are simply not allowed,
     * and sending them to a login page would invite them to try the same credentials again.</p>
     *
     * <p>Worth knowing that MockMvc does not perform the error dispatch, so in tests the
     * original behaviour appeared as a clean 403 and only the deployed service showed the 401.
     * The test for this asserts the redirect, which both environments agree on.</p>
     */
    static class StaleFormHandler implements AccessDeniedHandler {

        private final AccessDeniedHandler forbidden = new AccessDeniedHandlerImpl();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException denied) throws IOException, ServletException {
            if (denied instanceof CsrfException) {
                response.sendRedirect(request.getContextPath() + "/admin/login?expired");
                return;
            }
            forbidden.handle(request, response, denied);
        }
    }

    static class AdminLoginRecorder extends SavedRequestAwareAuthenticationSuccessHandler {

        private final AdminUserRepository admins;

        AdminLoginRecorder(AdminUserRepository admins) {
            this.admins = admins;
            setDefaultTargetUrl("/admin/players");
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request,
                                            HttpServletResponse response,
                                            Authentication authentication)
                throws IOException, ServletException {
            admins.findByUsernameIgnoreCase(authentication.getName())
                    .ifPresent(admin -> {
                        admin.setLastLoginAt(Instant.now());
                        // Saved explicitly: this runs outside any transaction of ours, so
                        // there is no persistence context that would flush the change on
                        // its own. The repository's own save() supplies the transaction.
                        admins.save(admin);
                    });

            log.info("admin '{}' signed in", authentication.getName());
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }
}
