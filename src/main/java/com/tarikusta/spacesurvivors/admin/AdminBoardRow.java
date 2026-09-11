package com.tarikusta.spacesurvivors.admin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * One row of the backoffice leaderboard.
 *
 * <p>Deliberately not {@link com.tarikusta.spacesurvivors.leaderboard.BoardRow}. That one is
 * shaped for the public board and leaves the player id in the database on purpose — nobody
 * playing the game needs to know it. This page does: the id is what a removal is aimed at,
 * and a name would be the wrong thing to aim with, since names can be changed and two rows
 * can be one rename apart.</p>
 *
 * @param achievedAt when the run was recorded — the column that makes an implausible score
 *                   recognisable, because a suspicious run and the account that made it
 *                   usually appear within minutes of each other
 */
public record AdminBoardRow(
        UUID playerId,
        String displayName,
        String mode,
        double survivedSeconds,
        int kills,
        int reachedLevel,
        int bossesDefeated,
        Instant achievedAt) {

    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneOffset.UTC).withLocale(Locale.ROOT);

    /**
     * {@code 15:45} — how the game shows a time, rather than how the column stores it.
     *
     * <p>{@code String.format} with an explicit {@code Locale.ROOT} rather than
     * {@code "…".formatted(…)}, which uses the JVM default: a locale whose numbering system
     * is not Western Arabic would render these digits in its own script.</p>
     */
    public String clock() {
        int total = (int) Math.round(survivedSeconds);
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    /** Formatted here rather than in the template: an Instant has no calendar of its own. */
    public String achievedOn() {
        return achievedAt == null ? "—" : MINUTE.format(achievedAt);
    }
}
