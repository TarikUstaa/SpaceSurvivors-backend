package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.auth.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Writes the audit trail.
 *
 * <p><b>One method per kind of event, rather than a general {@code record(action, target,
 * text)}.</b> A general method leaves the wording to whoever calls it, and five call sites
 * produce five vocabularies — the table fills with sentences that cannot be compared and
 * an {@code action} column that cannot be filtered. Here the sentence for "a player was
 * deleted" is written once, in one place, and every such row reads the same.</p>
 *
 * <h2>What happens when the audit write fails</h2>
 *
 * <p>Two different answers, on purpose.</p>
 *
 * <p>For the actions that change something — {@link #playerDeleted}, {@link #scoreRemoved},
 * {@link #passwordChanged} — this class does nothing to contain a failure. The caller is
 * {@code @Transactional} and the insert joins that transaction, so if the audit row cannot be
 * written the whole thing rolls back and the deletion never happened. That is the property
 * worth having: <em>no destructive action without a record of it</em>. The reverse ordering —
 * delete, then try to log — is what produces a database that quietly disagrees with its own
 * history.</p>
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

    private final AdminAuditRepository entries;

    public AdminAudit(AdminAuditRepository entries) {
        this.entries = entries;
    }

    // ── the destructive ones: a failure here must take the action down with it ──────────

    public void playerDeleted(String actor, UUID playerId, String displayName,
                              HttpServletRequest request) {
        // The display name goes into the sentence rather than staying a reference, because
        // seconds after this row is written there is no player_profile left to look it up in.
        write(actor, AdminAction.PLAYER_DELETED, String.valueOf(playerId),
                "deleted player '" + displayName + "', their save and their scores", request);
    }

    public void scoreRemoved(String actor, UUID playerId, String mode,
                             HttpServletRequest request) {
        write(actor, AdminAction.SCORE_REMOVED, String.valueOf(playerId),
                "removed the " + mode + " score", request);
    }

    public void passwordChanged(String actor, HttpServletRequest request) {
        write(actor, AdminAction.PASSWORD_CHANGED, actor, "changed their own password", request);
    }

    // ── the sign-in ones: never allowed to break the sign-in itself ────────────────────

    public void signedIn(String actor, HttpServletRequest request) {
        quietly(() -> write(actor, AdminAction.SIGNED_IN, null, "signed in", request));
    }

    /**
     * @param attempted the username that was typed, which is not necessarily an account —
     *                  stored as the actor because on this row the actor is exactly "whoever
     *                  claimed to be this"
     */
    public void signInFailed(String attempted, HttpServletRequest request) {
        quietly(() -> write(attempted, AdminAction.SIGN_IN_FAILED, null,
                "sign-in refused", request));
    }

    // ── plumbing ───────────────────────────────────────────────────────────────────────

    private void write(String actor, AdminAction action, String target, String summary,
                       HttpServletRequest request) {
        entries.save(new AdminAuditEntry(
                clip(blankToUnknown(actor), MAX_ACTOR),
                action,
                clip(target, MAX_TARGET),
                clip(summary, MAX_SUMMARY),
                request == null ? null : ClientAddress.of(request)));
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
