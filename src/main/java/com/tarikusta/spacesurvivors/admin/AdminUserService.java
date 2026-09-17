package com.tarikusta.spacesurvivors.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who may sign in to the backoffice, and as what.
 *
 * <p><b>Every method is ADMIN only</b>, by annotation on the method and not only by the URL rule
 * in front of the page — see {@link AdminSecurityConfig}. This is the class where a missing check
 * costs the most: a SUPPORT account that could reach {@link #changeRole} could make itself ADMIN.</p>
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Nobody chooses a password for somebody else.</b> Creating an account and resetting one
 *       both generate a temporary password, show it once, and flag the account so that its owner
 *       has to replace it before doing anything else. Letting the administrator type one would
 *       mean the administrator knows it — for as long as the owner does not change it, which
 *       without the flag could be forever.</li>
 *   <li><b>Nobody acts on their own account here.</b> Not their role, not their enabled flag, not
 *       their password (their own password has its own page, which asks for the current one).
 *       This is what guarantees the backoffice can never lock itself out: every change here is
 *       made by an enabled ADMIN to somebody else, so after any of them the actor is still an
 *       enabled ADMIN.</li>
 *   <li><b>No delete.</b> Disabling is the revocation, for the reason V3__admin_user.sql gives: it
 *       is reversible, and the audit trail's actor names keep meaning something.</li>
 * </ul>
 *
 * <p>Every outcome is returned rather than thrown. These are mistakes a person makes on a form,
 * not faults, and {@code ApiExceptionHandler} would turn an exception into a JSON body.</p>
 */
@Service
@PreAuthorize(AdminRole.IS_ADMIN)
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    /**
     * Letters, digits and {@code . _ -}, 3 to 32 long. The length is V3's CHECK; the character set
     * is a choice made here — a username is shown in the audit trail and typed into a login form,
     * and neither is improved by spaces, quotes or look-alike Unicode.
     */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,32}$");

    /**
     * No {@code 0 O 1 l I}: a temporary password is read off one screen and typed into another,
     * usually by a different person, and those are the characters that get misread.
     */
    private static final char[] ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneOffset.UTC).withLocale(Locale.ROOT);

    public enum Outcome {
        DONE,
        /** The account already was what was asked for. Nothing written, nothing audited. */
        UNCHANGED,
        INVALID_USERNAME,
        USERNAME_TAKEN,
        UNKNOWN_ROLE,
        NOT_ON_YOURSELF,
        NO_SUCH_ACCOUNT
    }

    /**
     * @param temporaryPassword present only when one was just generated. It exists in this record,
     *                          in one flash attribute and on one page render, and nowhere else —
     *                          the database holds its hash.
     */
    public record Result(Outcome outcome, String username, String temporaryPassword) {

        static Result of(Outcome outcome, String username) {
            return new Result(outcome, username, null);
        }
    }

    /** One row of the users page, with the times already formatted — see AdminAuditEntry.when(). */
    public record AccountRow(UUID id, String username, String role, boolean enabled,
                             boolean mustChangePassword, boolean twoFactor, String created,
                             String lastSignIn) {
    }

    private final AdminUserRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final AdminAudit audit;
    private final AdminTwoFactorService twoFactor;
    private final int passwordLength;
    private final SecureRandom random = new SecureRandom();

    public AdminUserService(AdminUserRepository admins,
                            PasswordEncoder passwordEncoder,
                            AdminAudit audit,
                            AdminTwoFactorService twoFactor,
                            @Value("${app.admin.min-password-length:12}") int minPassword) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.twoFactor = twoFactor;
        // Never shorter than the rule every other password here has to meet.
        this.passwordLength = Math.max(16, minPassword);
    }

    @Transactional(readOnly = true)
    public List<AccountRow> accounts() {
        return admins.findAllByOrderByCreatedAtAsc().stream()
                .map(a -> new AccountRow(a.getAdminId(), a.getUsername(), a.getRole(),
                        a.isEnabled(), a.isMustChangePassword(), a.hasTwoFactor(),
                        format(a.getCreatedAt()), format(a.getLastLoginAt())))
                .toList();
    }

    /**
     * Create an account with a generated temporary password.
     *
     * <p>The name is checked for before inserting rather than by catching the unique index's
     * violation. Catching it inside this transaction would mark the transaction rollback-only and
     * fail at commit with an exception that says nothing useful — the same trap D13 describes for
     * players. Two administrators creating the same name in the same instant would still meet the
     * index, and that is the right place for so unlikely a race to end.</p>
     */
    @Transactional
    public Result create(String actor, String username, String role, String callerIp) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches()) {
            return Result.of(Outcome.INVALID_USERNAME, name);
        }

        Optional<AdminRole> parsed = AdminRole.parse(role);
        if (parsed.isEmpty()) {
            return Result.of(Outcome.UNKNOWN_ROLE, name);
        }

        if (admins.findByUsernameIgnoreCase(name).isPresent()) {
            return Result.of(Outcome.USERNAME_TAKEN, name);
        }

        String temporary = temporaryPassword();
        AdminUser account = new AdminUser(name, passwordEncoder.encode(temporary), parsed.get());
        account.setMustChangePassword(true);
        admins.save(account);

        audit.accountCreated(actor, name, parsed.get(), callerIp);
        log.info("admin '{}' created {} account '{}'", actor, parsed.get(), name);
        return new Result(Outcome.DONE, name, temporary);
    }

    @Transactional
    public Result changeRole(String actor, UUID accountId, String role, String callerIp) {
        Optional<AdminRole> parsed = AdminRole.parse(role);
        if (parsed.isEmpty()) {
            return Result.of(Outcome.UNKNOWN_ROLE, null);
        }

        return onSomebodyElse(actor, accountId, account -> {
            String before = account.getRole();
            if (before.equals(parsed.get().name())) {
                return Result.of(Outcome.UNCHANGED, account.getUsername());
            }
            account.setRole(parsed.get());
            audit.accountRoleChanged(actor, account.getUsername(), before, parsed.get(), callerIp);
            log.info("admin '{}' changed '{}' from {} to {}",
                    actor, account.getUsername(), before, parsed.get());
            return Result.of(Outcome.DONE, account.getUsername());
        });
    }

    /**
     * Disable or re-enable an account. A disabled account that is signed in right now is signed
     * out on its very next request, by {@link AdminSessionGuard} — not whenever its session
     * happens to expire.
     */
    @Transactional
    public Result setEnabled(String actor, UUID accountId, boolean enabled, String callerIp) {
        return onSomebodyElse(actor, accountId, account -> {
            if (account.isEnabled() == enabled) {
                return Result.of(Outcome.UNCHANGED, account.getUsername());
            }
            account.setEnabled(enabled);
            audit.accountEnabled(actor, account.getUsername(), enabled, callerIp);
            log.info("admin '{}' {} '{}'", actor, enabled ? "re-enabled" : "disabled",
                    account.getUsername());
            return Result.of(Outcome.DONE, account.getUsername());
        });
    }

    /**
     * Replace somebody's password with a temporary one — for the person who has forgotten theirs.
     * The old password stops working at once; the new one works exactly once before its owner is
     * made to replace it.
     */
    @Transactional
    public Result resetPassword(String actor, UUID accountId, String callerIp) {
        return onSomebodyElse(actor, accountId, account -> {
            String temporary = temporaryPassword();
            account.setPasswordHash(passwordEncoder.encode(temporary));
            account.setMustChangePassword(true);
            // A reset is for a forgotten password — or one somebody else may know. Either way,
            // no session signed in with the old one should keep working.
            account.endAllSessions();
            audit.accountPasswordReset(actor, account.getUsername(), callerIp);
            log.info("admin '{}' reset the password of '{}'", actor, account.getUsername());
            return new Result(Outcome.DONE, account.getUsername(), temporary);
        });
    }

    /**
     * Remove another account's two-factor — for somebody who lost their phone and their recovery
     * codes. Their sessions end with it. Not for your own account: that is the security page,
     * which asks for both factors.
     */
    @Transactional
    public Result resetTwoFactor(String actor, UUID accountId, String callerIp) {
        return onSomebodyElse(actor, accountId, account -> {
            if (!account.hasTwoFactor()) {
                return Result.of(Outcome.UNCHANGED, account.getUsername());
            }
            twoFactor.reset(actor, account, callerIp);
            return Result.of(Outcome.DONE, account.getUsername());
        });
    }

    // ── plumbing ───────────────────────────────────────────────────────────────────────

    private interface Change {
        Result apply(AdminUser account);
    }

    /**
     * Load the target, refuse if it is the actor, otherwise apply. Compared by id, not by name,
     * so that {@code Admin} and {@code admin} — which are one account — cannot be used to slip
     * past the rule. The entity is managed, so the change is written at commit without a save.
     */
    private Result onSomebodyElse(String actor, UUID accountId, Change change) {
        AdminUser account = accountId == null ? null : admins.findById(accountId).orElse(null);
        if (account == null) {
            return Result.of(Outcome.NO_SUCH_ACCOUNT, null);
        }

        boolean isActor = admins.findByUsernameIgnoreCase(actor)
                .map(me -> me.getAdminId().equals(account.getAdminId()))
                .orElse(false);
        if (isActor) {
            return Result.of(Outcome.NOT_ON_YOURSELF, account.getUsername());
        }

        return change.apply(account);
    }

    private String temporaryPassword() {
        char[] out = new char[passwordLength];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    private static String format(Instant at) {
        return at == null ? "—" : MINUTE.format(at);
    }
}
