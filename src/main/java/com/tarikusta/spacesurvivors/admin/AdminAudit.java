package com.tarikusta.spacesurvivors.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Reads and writes the audit trail.
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

    /**
     * How many entries the page draws. Large enough that a day's work fits, small enough that
     * the page stays one query and one screenful of HTML.
     */
    private static final int RECENT = 200;

    private final AdminAuditRepository store;

    public AdminAudit(AdminAuditRepository store) {
        this.store = store;
    }

    // ── reading ────────────────────────────────────────────────────────────────────────

    /**
     * The recent entries plus the count of all of them, so the page can say it is showing a
     * slice rather than implying it is showing everything.
     */
    public record Trail(List<AdminAuditEntry> entries, long total, int limit) {

        public int shown() {
            return entries.size();
        }

        public boolean truncated() {
            return total > entries.size();
        }
    }

    @Transactional(readOnly = true)
    public Trail recent() {
        List<AdminAuditEntry> page = store.findAllByOrderByHappenedAtDescAuditIdDesc(Limit.of(RECENT));
        return new Trail(page, store.count(), RECENT);
    }

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

    public void passwordChanged(String actor, String callerIp) {
        write(actor, AdminAction.PASSWORD_CHANGED, actor, "changed their own password", callerIp);
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
