package com.tarikusta.spacesurvivors.admin;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

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
