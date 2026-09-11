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
    SCORE_REMOVED
}
