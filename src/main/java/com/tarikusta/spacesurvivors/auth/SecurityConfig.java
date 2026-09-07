package com.tarikusta.spacesurvivors.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Who may call what, and how a token is signed and checked.
 *
 * <p>This replaces the hand-written filter that used to read a device id and believe it.
 * Spring Security now verifies a signature before a request reaches any controller, so an
 * unauthenticated call is refused by the framework rather than by our code remembering to
 * ask.</p>
 */
@Configuration
public class SecurityConfig {

    /** Paths that must answer without a token, and why each one has to. */
    private static final String[] PUBLIC = {
            "/health",          // a probe has no credential to offer
            "/actuator/**",
            "/v1/auth/token",   // where a token is obtained; requiring one here is a circle
            "/v3/api-docs/**",  // the document explaining which credential to send
            "/swagger-ui/**",
            "/swagger-ui.html",
    };

    @Bean
    public SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                // No browser, no cookies, no sessions: every request carries its own token,
                // so there is nothing for a forged cross-site request to ride on.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                // Answer 401 rather than redirect to a login form that does not exist.
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /**
     * BCrypt, not a plain hash. It is deliberately slow and salts every row separately,
     * which is what makes a stolen table impractical to attack offline — the property a
     * fast hash like SHA-256 does not have.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * One symmetric key signs and verifies, because one service does both. A pair of RSA
     * keys would only earn its complexity if some other service had to verify tokens
     * without being able to mint them.
     */
    private SecretKeySpec signingKey(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(@Value("${app.jwt.secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(secret)));
    }

    @Bean
    public JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        return NimbusJwtDecoder.withSecretKey(signingKey(secret))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
