package com.tarikusta.spacesurvivors.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Two-factor sign-in for backoffice accounts: turning it on and off, and checking the second step.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Turning it on or off asks for the password again</b>, and turning it off also asks for a
 *       code. A session somebody else is holding must not be enough to enrol their own phone —
 *       that would lock the real owner out — nor to remove the factor that was protecting the
 *       account.</li>
 *   <li><b>The secret is saved only once a code from it has been checked.</b> Until then it lives in
 *       the session. An app that scanned it wrong, or a clock that is off, fails here — not at the
 *       next sign-in, when there is no way back in.</li>
 *   <li><b>A code works once.</b> The step it belongs to must be later than the last one accepted
 *       ({@code totp_last_step}).</li>
 *   <li><b>Eight recovery codes</b>, shown once when two-factor is turned on, each good for one
 *       sign-in. Hashed like passwords.</li>
 * </ul>
 */
@Service
public class AdminTwoFactorService {

    private static final Logger log = LoggerFactory.getLogger(AdminTwoFactorService.class);

    static final String ISSUER = "SpaceSurvivors";
    static final int RECOVERY_CODES = 8;

    /** No {@code 0 o 1 l i}, for the reason AdminUserService gives about temporary passwords. */
    private static final char[] CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    public enum Outcome { DONE, WRONG_PASSWORD, WRONG_CODE, ALREADY_ON, NOT_ON, NO_SUCH_ADMIN }

    /** @param recoveryCodes present only when two-factor was just turned on */
    public record Result(Outcome outcome, List<String> recoveryCodes) {

        static Result of(Outcome outcome) {
            return new Result(outcome, List.of());
        }
    }

    /** A secret offered for enrolment and the link an authenticator app accepts. Not saved yet. */
    public record Enrollment(String secret, String uri) implements java.io.Serializable {
    }

    /** @param remainingRecoveryCodes meaningful only when two-factor is on */
    public record Status(boolean enabled, int remainingRecoveryCodes) {
    }

    private final AdminUserRepository admins;
    private final JdbcClient db;
    private final PasswordEncoder passwordEncoder;
    private final AdminAudit audit;
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();

    public AdminTwoFactorService(AdminUserRepository admins, JdbcClient db,
                                 PasswordEncoder passwordEncoder, AdminAudit audit) {
        this.admins = admins;
        this.db = db;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Status status(String username) {
        return admins.findByUsernameIgnoreCase(username)
                .map(a -> new Status(a.hasTwoFactor(), a.hasTwoFactor() ? remainingCodes(a.getAdminId()) : 0))
                .orElse(new Status(false, 0));
    }

    public boolean requiresSecondStep(String username) {
        return admins.findByUsernameIgnoreCase(username).map(AdminUser::hasTwoFactor).orElse(false);
    }

    /** A fresh secret to enrol. Nothing is written — see the class comment. */
    public Enrollment begin(String username) {
        String secret = Totp.newSecret();
        return new Enrollment(secret, Totp.otpauthUri(ISSUER, username, secret));
    }

    @Transactional
    public Result enable(String username, String currentPassword, String pendingSecret, String code,
                         String callerIp) {
        AdminUser admin = admins.findByUsernameIgnoreCase(username).orElse(null);
        if (admin == null) {
            return Result.of(Outcome.NO_SUCH_ADMIN);
        }
        if (admin.hasTwoFactor()) {
            return Result.of(Outcome.ALREADY_ON);
        }
        if (!passwordEncoder.matches(nullToEmpty(currentPassword), admin.getPasswordHash())) {
            return Result.of(Outcome.WRONG_PASSWORD);
        }
        OptionalLong step = pendingSecret == null ? OptionalLong.empty()
                : Totp.matchingStep(pendingSecret, normalise(code), clock.instant().getEpochSecond());
        if (step.isEmpty()) {
            return Result.of(Outcome.WRONG_CODE);
        }

        admin.setTotpSecret(pendingSecret);
        admin.setTotpLastStep(step.getAsLong());
        admins.save(admin);

        List<String> codes = replaceRecoveryCodes(admin.getAdminId());
        audit.twoFactorEnabled(admin.getUsername(), callerIp);
        log.info("admin '{}' turned on two-factor sign-in", admin.getUsername());
        return new Result(Outcome.DONE, codes);
    }

    @Transactional
    public Result disable(String username, String currentPassword, String code, String callerIp) {
        AdminUser admin = admins.findByUsernameIgnoreCase(username).orElse(null);
        if (admin == null) {
            return Result.of(Outcome.NO_SUCH_ADMIN);
        }
        if (!admin.hasTwoFactor()) {
            return Result.of(Outcome.NOT_ON);
        }
        if (!passwordEncoder.matches(nullToEmpty(currentPassword), admin.getPasswordHash())) {
            return Result.of(Outcome.WRONG_PASSWORD);
        }
        if (!acceptSecondFactor(admin, code, callerIp, false)) {
            return Result.of(Outcome.WRONG_CODE);
        }

        turnOff(admin);
        audit.twoFactorDisabled(admin.getUsername(), callerIp);
        log.info("admin '{}' turned off two-factor sign-in", admin.getUsername());
        return Result.of(Outcome.DONE);
    }

    /**
     * The second step of a sign-in: a code from the app, or one unused recovery code.
     *
     * @return true when the sign-in may complete
     */
    @Transactional
    public boolean verifySignIn(String username, String code, String callerIp) {
        AdminUser admin = admins.findByUsernameIgnoreCase(username).orElse(null);
        if (admin == null || !admin.hasTwoFactor()) {
            return false;
        }
        if (!acceptSecondFactor(admin, code, callerIp, true)) {
            audit.twoFactorFailed(admin.getUsername(), callerIp);
            log.warn("admin '{}' gave a wrong second factor", admin.getUsername());
            return false;
        }
        // Stamped here rather than at the password step: the sign-in is not complete until now.
        admin.setLastLoginAt(java.time.Instant.now(clock));
        admins.save(admin);
        audit.signedInWithTwoFactor(admin.getUsername(), callerIp);
        return true;
    }

    /**
     * Another administrator removes an account's two-factor — the answer to a lost phone. Every
     * session of that account ends: whoever holds one may be the reason the phone is "lost".
     * The caller ({@link AdminUserService}) has already checked the role and that it is not the
     * actor's own account.
     */
    @Transactional
    void reset(String actor, AdminUser account, String callerIp) {
        turnOff(account);
        account.endAllSessions();
        audit.twoFactorReset(actor, account.getUsername(), callerIp);
        log.info("admin '{}' removed two-factor sign-in from '{}'", actor, account.getUsername());
    }

    // ── plumbing ───────────────────────────────────────────────────────────────────────

    /**
     * A six-digit code is checked against the app's secret; anything else is tried as a recovery
     * code. Recovery codes are only accepted for a sign-in — disabling two-factor wants the real
     * device, or an administrator.
     */
    private boolean acceptSecondFactor(AdminUser admin, String code, String callerIp,
                                       boolean allowRecovery) {
        String typed = normalise(code);
        OptionalLong step = Totp.matchingStep(admin.getTotpSecret(), typed,
                clock.instant().getEpochSecond());
        if (step.isPresent()) {
            Long last = admin.getTotpLastStep();
            if (last != null && step.getAsLong() <= last) {
                return false;   // this code, or an earlier one, was already used
            }
            admin.setTotpLastStep(step.getAsLong());
            admins.save(admin);
            return true;
        }
        return allowRecovery && useRecoveryCode(admin, typed, callerIp);
    }

    private boolean useRecoveryCode(AdminUser admin, String typed, String callerIp) {
        if (typed.length() != 8) {
            return false;
        }
        record Code(long id, String hash) {
        }
        List<Code> unused = db.sql("""
                        SELECT code_id, code_hash FROM admin_recovery_code
                         WHERE admin_id = :a AND used_at IS NULL""")
                .param("a", admin.getAdminId())
                .query((rs, n) -> new Code(rs.getLong("code_id"), rs.getString("code_hash")))
                .list();

        for (Code candidate : unused) {
            if (passwordEncoder.matches(typed, candidate.hash())) {
                // "AND used_at IS NULL" again, so two requests racing with the same code cannot
                // both succeed: the second update touches no row.
                int claimed = db.sql("""
                                UPDATE admin_recovery_code SET used_at = now()
                                 WHERE code_id = :id AND used_at IS NULL""")
                        .param("id", candidate.id()).update();
                if (claimed == 1) {
                    audit.recoveryCodeUsed(admin.getUsername(), unused.size() - 1, callerIp);
                    log.warn("admin '{}' signed in with a recovery code, {} left",
                            admin.getUsername(), unused.size() - 1);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private List<String> replaceRecoveryCodes(UUID adminId) {
        db.sql("DELETE FROM admin_recovery_code WHERE admin_id = :a").param("a", adminId).update();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String code = recoveryCode();
            codes.add(code.substring(0, 4) + "-" + code.substring(4));
            db.sql("INSERT INTO admin_recovery_code (admin_id, code_hash) VALUES (:a, :h)")
                    .param("a", adminId).param("h", passwordEncoder.encode(code)).update();
        }
        return codes;
    }

    private void turnOff(AdminUser admin) {
        admin.setTotpSecret(null);
        admins.save(admin);
        db.sql("DELETE FROM admin_recovery_code WHERE admin_id = :a").param("a", admin.getAdminId()).update();
    }

    private int remainingCodes(UUID adminId) {
        return db.sql("SELECT count(*) FROM admin_recovery_code WHERE admin_id = :a AND used_at IS NULL")
                .param("a", adminId).query(Integer.class).single();
    }

    private String recoveryCode() {
        char[] out = new char[8];
        for (int i = 0; i < out.length; i++) {
            out[i] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        }
        return new String(out);
    }

    /** "123 456", "abcd-efgh", " ABCD EFGH " — people type codes every way. */
    private static String normalise(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
