package com.tarikusta.spacesurvivors.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing an administrator's own password.
 *
 * <p>A service rather than a few lines in the controller, because the rules below are the
 * whole feature and none of them are about HTTP. Tested directly, without a browser or a
 * form.</p>
 */
@Service
public class AdminAccountService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    /** What happened, in the caller's terms. A page turns each of these into a sentence. */
    public enum Result {
        CHANGED,
        WRONG_CURRENT_PASSWORD,
        TOO_SHORT,
        SAME_AS_CURRENT,
        NO_SUCH_ADMIN
    }

    private final AdminUserRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final int minPassword;

    public AdminAccountService(AdminUserRepository admins,
                               PasswordEncoder passwordEncoder,
                               @Value("${app.admin.min-password-length:12}") int minPassword) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.minPassword = minPassword;
    }

    public int minimumPasswordLength() {
        return minPassword;
    }

    /**
     * Set a new password, after proving the old one.
     *
     * <p><b>Why the current password is asked for at all,</b> given that the caller is already
     * signed in: a session that is already open is exactly the thing this protects against.
     * An unattended laptop, a borrowed browser, a stolen session cookie — in every one of
     * those the attacker has the session and not the password, and without this check their
     * first move is to change the password and own the account outright. Asking for it turns
     * a temporary hold into one that ends when the session does.</p>
     *
     * <p>The comparison is {@link PasswordEncoder#matches}, never string equality on hashes.
     * BCrypt embeds a random salt in every hash, so encoding the same password twice produces
     * two different strings and {@code encode(given).equals(stored)} is false for the correct
     * password. That mistake fails closed, which is why it survives in codebases: everything
     * looks secure and nobody can ever sign in.</p>
     */
    @Transactional
    public Result changePassword(String username, String currentPassword, String newPassword) {
        AdminUser admin = admins.findByUsernameIgnoreCase(username).orElse(null);
        if (admin == null) {
            return Result.NO_SUCH_ADMIN;
        }

        if (!passwordEncoder.matches(nullToEmpty(currentPassword), admin.getPasswordHash())) {
            // Logged as a warning: somebody with a live session failing to prove the password
            // is worth seeing, because that is the shape of a hijacked session.
            log.warn("admin '{}' gave the wrong current password when changing it", username);
            return Result.WRONG_CURRENT_PASSWORD;
        }

        String next = nullToEmpty(newPassword);
        if (next.length() < minPassword) {
            return Result.TOO_SHORT;
        }

        // Refused rather than quietly accepted. A form that reports success without changing
        // anything teaches its user that the button does nothing.
        if (passwordEncoder.matches(next, admin.getPasswordHash())) {
            return Result.SAME_AS_CURRENT;
        }

        admin.setPasswordHash(passwordEncoder.encode(next));
        admins.save(admin);

        log.info("admin '{}' changed their password", username);
        return Result.CHANGED;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
