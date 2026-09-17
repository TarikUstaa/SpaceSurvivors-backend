package com.tarikusta.spacesurvivors.admin;

/**
 * Everything the backoffice records about itself.
 *
 * <p>An enum rather than a string parameter, so that the set of things this application
 * admits to doing is a list somebody can read in one place. A {@code record(String action,
 * ...)} would have been shorter to write and would have drifted within a month: one call site
 * writes {@code "delete_player"}, the next {@code "PLAYER DELETED"}, and the table can no
 * longer be filtered by what happened.</p>
 *
 * <p>Adding a case here is the moment to ask whether the event is worth a row. The bar used
 * so far: <em>something changed, or somebody tried to get in</em>. Page views are not on the
 * list — an audit table that fills with reads is one nobody scrolls to the bottom of.</p>
 */
public enum AdminAction {

    /** A successful sign-in. The baseline every other row is read against. */
    SIGNED_IN,

    /**
     * A rejected sign-in. Recorded with the username that was tried and the address it came
     * from; neither is trusted, both are evidence. The rate limiter caps how many of these a
     * single caller can produce, so this cannot be used to flood the table.
     */
    SIGN_IN_FAILED,

    /** An administrator changed their own password. */
    PASSWORD_CHANGED,

    /** A player, their save and their scores were deleted. The irreversible one. */
    PLAYER_DELETED,

    /** One leaderboard row was removed. The player kept everything else. */
    SCORE_REMOVED,

    /**
     * A leaderboard row was written by hand. The summary carries the row it replaced, if any —
     * after the write, nothing else does.
     */
    SCORE_SET,

    /**
     * The audit trail was downloaded. It holds sign-in addresses and the record of every deletion,
     * so a copy leaving the application is itself worth a line.
     */
    AUDIT_EXPORTED,

    /** The main-menu announcement was published or replaced. The text is in the summary. */
    ANNOUNCEMENT_SET,

    /** The main-menu announcement was taken down. */
    ANNOUNCEMENT_CLEARED,

    /** A player's display name was changed from the backoffice — usually a name report. */
    PLAYER_RENAMED,

    /** A test player was made up in the backoffice. Nobody can sign in as it. */
    TEST_PLAYER_CREATED,

    /** Every test player was removed at once, with their saves and scores. */
    TEST_PLAYERS_DELETED,

    /**
     * A player's cloud save was edited by hand. The summary lists every field that changed, old
     * value and new — the save itself is overwritten, so this row is the only place the previous
     * numbers survive.
     */
    PROGRESS_EDITED,

    /** An account turned two-factor sign-in on for itself. */
    TWO_FACTOR_ENABLED,

    /** An account turned two-factor sign-in off for itself, proving both factors to do it. */
    TWO_FACTOR_DISABLED,

    /** An administrator removed another account's two-factor — the lost-phone path. */
    TWO_FACTOR_RESET,

    /**
     * The password was right and the second factor was not. Worth more attention than a plain
     * refused sign-in: whoever did this already has the password.
     */
    TWO_FACTOR_FAILED,

    /** A one-time recovery code completed a sign-in. The owner has fewer left, and lost a device. */
    RECOVERY_CODE_USED,

    /** A backoffice account was created, with a temporary password its owner must replace. */
    ACCOUNT_CREATED,

    /** A backoffice account's role changed. Takes effect on that account's next request. */
    ACCOUNT_ROLE_CHANGED,

    /** A backoffice account was disabled — signed out on its next request — or re-enabled. */
    ACCOUNT_DISABLED,
    ACCOUNT_ENABLED,

    /**
     * Somebody else's password was replaced with a temporary one. Distinct from
     * {@link #PASSWORD_CHANGED}, which is an account changing its <em>own</em> password: this one
     * is the event that briefly lets one person sign in as another, and deserves its own name.
     */
    ACCOUNT_PASSWORD_RESET
}
