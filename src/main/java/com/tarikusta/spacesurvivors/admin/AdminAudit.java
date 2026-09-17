package com.tarikusta.spacesurvivors.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Writes the audit trail. Reading it is {@link AdminAuditSearch}.
 *
 * <p><b>One method per kind of event, rather than a general {@code record(action, target,
 * text)}.</b> A general method leaves the wording to whoever calls it, and five call sites
 * produce five vocabularies — the table fills with sentences that cannot be compared and
 * an {@code action} column that cannot be filtered. Here the sentence for "a player was
 * deleted" is written once, in one place, and every such row reads the same.</p>
 *
 * <p><b>The caller's address arrives as a {@code String}, not as an {@code HttpServletRequest}.</b>
 * It used to be the request, and that single parameter quietly decided where every audit call
 * could live: only code that holds a request can call a method that needs one, so every write
 * had to happen in a controller, and the rules that surround those writes ended up there with
 * them. A service that takes a string can be called from another service. The web layer
 * resolves the address with {@code ClientAddress.of(request)} and passes the answer along.</p>
 *
 * <h2>What happens when the audit write fails</h2>
 *
 * <p>Two different answers, on purpose.</p>
 *
 * <p>For the actions that change something — {@link #playerDeleted}, {@link #scoreRemoved},
 * {@link #passwordChanged} — this class does nothing to contain a failure. Its caller is a
 * {@code @Transactional} service method and the insert joins that transaction, so if the audit
 * row cannot be written the whole thing rolls back and the deletion never happened. That is the
 * property worth having: <em>no destructive action without a record of it</em>. The reverse
 * ordering — delete, then try to log — is what produces a database that quietly disagrees with
 * its own history.</p>
 *
 * <p>For {@link #signedIn} and {@link #signInFailed} the failure is swallowed and logged.
 * These run inside Spring Security's handlers, outside any transaction of ours, and the
 * action they describe has already happened by the time they are reached. Letting a failed
 * insert throw there would turn a broken log table into an administrator who cannot get in —
 * a logging fault escalated into a lockout, over the least consequential rows in the table.</p>
 */
@Service
public class AdminAudit {

    private static final Logger log = LoggerFactory.getLogger(AdminAudit.class);

    /**
     * Nothing that reaches this table is unbounded. {@code actor} can be a username typed
     * into a login form by anyone who can reach the page, and a {@code text} column will
     * happily store a megabyte of it; the rate limiter caps how often, not how large. The
     * summaries are built from display names, which are bounded elsewhere — clipped anyway,
     * because "bounded elsewhere" is a fact about today's code.
     */
    private static final int MAX_ACTOR = 64;
    private static final int MAX_TARGET = 128;
    private static final int MAX_SUMMARY = 512;

    private final AdminAuditRepository store;

    public AdminAudit(AdminAuditRepository store) {
        this.store = store;
    }

    // Reading the trail is AdminAuditSearch's job; this class only writes it.

    // ── the destructive ones: a failure here must take the action down with it ──────────

    public void playerDeleted(String actor, UUID playerId, String displayName, String callerIp) {
        // The display name goes into the sentence rather than staying a reference, because
        // seconds after this row is written there is no player_profile left to look it up in.
        write(actor, AdminAction.PLAYER_DELETED, String.valueOf(playerId),
                "deleted player '" + displayName + "', their save and their scores", callerIp);
    }

    public void scoreRemoved(String actor, UUID playerId, String mode, String callerIp) {
        write(actor, AdminAction.SCORE_REMOVED, String.valueOf(playerId),
                "removed the " + mode + " score", callerIp);
    }

    /**
     * @param before the row being replaced, already worded, or null if the player had no score in
     *               that mode
     * @param after  the row as written, already worded
     */
    public void scoreSet(String actor, UUID playerId, String displayName, String mode,
                         String before, String after, String callerIp) {
        write(actor, AdminAction.SCORE_SET, String.valueOf(playerId),
                "set the " + mode + " score of '" + displayName + "' to " + after
                + (before == null ? " (no previous score)" : " (was " + before + ")"),
                callerIp);
    }

    /**
     * Not one of the destructive writes, and not contained like the sign-in ones either: an export
     * that cannot be recorded should fail rather than hand the file over unrecorded.
     */
    public void twoFactorEnabled(String actor, String callerIp) {
        write(actor, AdminAction.TWO_FACTOR_ENABLED, actor, "turned on two-factor sign-in", callerIp);
    }

    public void twoFactorDisabled(String actor, String callerIp) {
        write(actor, AdminAction.TWO_FACTOR_DISABLED, actor, "turned off two-factor sign-in", callerIp);
    }

    public void twoFactorReset(String actor, String username, String callerIp) {
        write(actor, AdminAction.TWO_FACTOR_RESET, username,
                "removed two-factor sign-in from '" + username + "'", callerIp);
    }

    public void recoveryCodeUsed(String actor, int remaining, String callerIp) {
        write(actor, AdminAction.RECOVERY_CODE_USED, actor,
                "signed in with a recovery code (" + remaining + " left)", callerIp);
    }

    /**
     * Written quietly, like the other sign-in rows: a broken audit table must not become a way to
     * lock somebody out of the second step either.
     */
    public void twoFactorFailed(String actor, String callerIp) {
        quietly(() -> write(actor, AdminAction.TWO_FACTOR_FAILED, actor,
                "second factor refused", callerIp));
    }

    public void signedInWithTwoFactor(String actor, String callerIp) {
        quietly(() -> write(actor, AdminAction.SIGNED_IN, null, "signed in (two-factor)", callerIp));
    }

    public void auditExported(String actor, int rows, boolean truncated, String filter,
                              String callerIp) {
        write(actor, AdminAction.AUDIT_EXPORTED, null,
                "exported " + rows + " audit entries (" + filter + ")"
                + (truncated ? ", cut at the export limit" : ""), callerIp);
    }

    public void playerRenamed(String actor, UUID playerId, String from, String to,
                              String callerIp) {
        write(actor, AdminAction.PLAYER_RENAMED, String.valueOf(playerId),
                "renamed player '" + from + "' to '" + to + "'", callerIp);
    }

    public void testPlayersDeleted(String actor, int count, String callerIp) {
        write(actor, AdminAction.TEST_PLAYERS_DELETED, null,
                "deleted all " + count + " test players, their saves and their scores", callerIp);
    }

    public void testPlayerCreated(String actor, UUID playerId, String displayName,
                                  String callerIp) {
        write(actor, AdminAction.TEST_PLAYER_CREATED, String.valueOf(playerId),
                "created test player '" + displayName + "'", callerIp);
    }

    public void passwordChanged(String actor, String callerIp) {
        write(actor, AdminAction.PASSWORD_CHANGED, actor, "changed their own password", callerIp);
    }

    /**
     * @param changes one entry per field, already worded ({@code "wallet 120 → 5000"}). The
     *                caller builds them because only the caller knows the before and after; this
     *                method only decides how a list of them reads.
     */
    public void progressEdited(String actor, UUID playerId, String displayName,
                               List<String> changes, String callerIp) {
        write(actor, AdminAction.PROGRESS_EDITED, String.valueOf(playerId),
                "edited the save of '" + displayName + "': " + String.join(", ", changes),
                callerIp);
    }

    public void accountCreated(String actor, String username, AdminRole role, String callerIp) {
        write(actor, AdminAction.ACCOUNT_CREATED, username,
                "created " + role.name() + " account '" + username + "'", callerIp);
    }

    public void accountRoleChanged(String actor, String username, String from, AdminRole to,
                                   String callerIp) {
        write(actor, AdminAction.ACCOUNT_ROLE_CHANGED, username,
                "changed '" + username + "' from " + from + " to " + to.name(), callerIp);
    }

    public void accountEnabled(String actor, String username, boolean enabled, String callerIp) {
        write(actor, enabled ? AdminAction.ACCOUNT_ENABLED : AdminAction.ACCOUNT_DISABLED,
                username, (enabled ? "re-enabled '" : "disabled '") + username + "'", callerIp);
    }

    public void accountPasswordReset(String actor, String username, String callerIp) {
        write(actor, AdminAction.ACCOUNT_PASSWORD_RESET, username,
                "reset the password of '" + username + "' to a temporary one", callerIp);
    }

    // ── the sign-in ones: never allowed to break the sign-in itself ────────────────────

    public void signedIn(String actor, String callerIp) {
        quietly(() -> write(actor, AdminAction.SIGNED_IN, null, "signed in", callerIp));
    }

    /**
     * @param attempted the username that was typed, which is not necessarily an account —
     *                  stored as the actor because on this row the actor is exactly "whoever
     *                  claimed to be this"
     */
    public void signInFailed(String attempted, String callerIp) {
        quietly(() -> write(attempted, AdminAction.SIGN_IN_FAILED, null,
                "sign-in refused", callerIp));
    }

    // ── plumbing ───────────────────────────────────────────────────────────────────────

    private void write(String actor, AdminAction action, String target, String summary,
                       String callerIp) {
        store.save(new AdminAuditEntry(
                clip(blankToUnknown(actor), MAX_ACTOR),
                action,
                clip(target, MAX_TARGET),
                clip(summary, MAX_SUMMARY),
                callerIp));
    }

    private void quietly(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            // Deliberately not rethrown — see the class comment. Logged at warn because a
            // silently missing audit row is the one failure mode this table cannot tolerate
            // going unnoticed.
            log.warn("could not write an audit entry", e);
        }
    }

    private static String blankToUnknown(String actor) {
        return actor == null || actor.isBlank() ? "(unknown)" : actor;
    }

    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }
}
