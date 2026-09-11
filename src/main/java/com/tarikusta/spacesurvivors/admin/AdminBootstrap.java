package com.tarikusta.spacesurvivors.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the very first administrator, once, from the environment.
 *
 * <p><b>Why not a seed row in the migration.</b> A migration is committed, so the password
 * hash it inserts is public, and a public BCrypt hash is a password everyone has — BCrypt
 * makes an offline attack slow, not impossible, and "admin/admin123" survives no time at
 * all. Every deployment would also share the same account. Reading the first credentials
 * from the environment keeps them out of the repository and lets each deployment have its
 * own.</p>
 *
 * <p><b>Why it only acts on an empty table.</b> Running on every start would make the
 * environment variable authoritative forever: a password changed through the backoffice
 * would be silently reset on the next restart, and removing the variable would do nothing.
 * As written, the variables matter exactly once — at the moment there is no way in — and
 * are inert afterwards.</p>
 *
 * <p>Unset variables are not an error. A deployment that already has an administrator has
 * no use for them, and failing to start would be a strange way to say "everything is
 * already fine". It logs what to do instead.</p>
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /** Matches the CHECK in V3__admin_user.sql, so a bad value fails here with a clear reason. */
    private static final int MIN_USERNAME = 3;
    private static final int MAX_USERNAME = 32;

    /**
     * Short enough to type, long enough to be worth typing. Twelve characters is the point
     * where an offline attack on a BCrypt hash stops being a weekend project; the database
     * has no opinion about it, so the check lives here.
     */
    private static final int MIN_PASSWORD = 12;

    private final AdminUserRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public AdminBootstrap(AdminUserRepository admins,
                          PasswordEncoder passwordEncoder,
                          @Value("${app.admin.bootstrap.username:}") String username,
                          @Value("${app.admin.bootstrap.password:}") String password) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (admins.count() > 0) {
            return;   // somebody can already get in; these settings are none of our business
        }

        if (username.isEmpty() || password.isEmpty()) {
            log.warn("""
                    No administrator exists and none can be created: \
                    app.admin.bootstrap.username / .password are unset. \
                    Set both (locally in application-local.properties, in a deployment as \
                    ADMIN_USERNAME / ADMIN_PASSWORD) and restart. The backoffice at /admin \
                    is unreachable until then.""");
            return;
        }

        if (username.length() < MIN_USERNAME || username.length() > MAX_USERNAME) {
            log.error("Administrator not created: username must be {}-{} characters.",
                    MIN_USERNAME, MAX_USERNAME);
            return;
        }

        if (password.length() < MIN_PASSWORD) {
            log.error("Administrator not created: password must be at least {} characters.",
                    MIN_PASSWORD);
            return;
        }

        // The plaintext goes straight into the encoder and is never held, logged or returned.
        admins.save(new AdminUser(username, passwordEncoder.encode(password), "ADMIN"));
        log.info("Created the first administrator '{}'. These settings do nothing from now on, "
                 + "so the variables can be removed once the sign-in is confirmed.", username);
    }
}
