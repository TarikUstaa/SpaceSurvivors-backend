package com.tarikusta.spacesurvivors.admin;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an {@code admin_user} row into the shape Spring Security checks passwords against.
 *
 * <p>This class does not verify anything. It answers "who claims this name, and what is
 * their stored hash" — {@code DaoAuthenticationProvider} then compares the submitted
 * password against that hash with the {@code PasswordEncoder} bean. Keeping the lookup and
 * the comparison apart is what lets the framework do the comparison correctly, which
 * matters more than it sounds: it hashes the submitted password even when no such user
 * exists, so that a wrong username and a wrong password take the same amount of time.
 * Without that, the response time alone tells an attacker which usernames are real.</p>
 *
 * <p>For the same reason {@link UsernameNotFoundException} is thrown rather than returned
 * as "bad password": the provider converts both into the same {@code BadCredentialsException}
 * and the login page says only that the sign-in failed. A page that distinguishes them is
 * a free account-enumeration tool.</p>
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    /** Spring Security's convention for authority names; the column stores the bare role. */
    private static final String ROLE_PREFIX = "ROLE_";

    private final AdminUserRepository admins;

    public AdminUserDetailsService(AdminUserRepository admins) {
        this.admins = admins;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        AdminUser admin = admins.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("no such administrator"));

        return User.withUsername(admin.getUsername())
                .password(admin.getPasswordHash())
                .authorities(ROLE_PREFIX + admin.getRole())
                // A disabled row is refused by the framework before any page is reached, so
                // revoking access is one UPDATE and needs no other code to cooperate.
                .disabled(!admin.isEnabled())
                .build();
    }
}
