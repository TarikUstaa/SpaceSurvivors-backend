package com.tarikusta.spacesurvivors.admin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * One line of the backoffice player list.
 *
 * <p>A projection, not an entity: the page needs five columns of {@code player_profile} plus
 * two counts from other tables, and loading whole entities to render that would fetch the
 * saved-game JSON — potentially kilobytes per player — for a screen that never shows it.</p>
 *
 * <p>{@code deviceId} and {@code lastIp} are deliberately absent. Neither helps anyone read
 * this list, and both are the kind of thing that ends up on a screen in a meeting. The
 * device id is also half of a player's credential.</p>
 *
 * @param boardEntries how many leaderboard rows this player holds (0-2: one per mode)
 * @param saves        1 if a cloud save exists, 0 if the player has only ever authenticated
 */
public record AdminPlayerRow(
        UUID playerId,
        String displayName,
        String country,
        Instant firstLoginDate,
        Instant updatedAt,
        Long boardEntries,
        Long saves) {

    public boolean hasSave() {
        return saves != null && saves > 0;
    }

    /**
     * Both timestamps are formatted here rather than in the template, and the reason is a
     * trap worth remembering: an {@link Instant} is a point on the timeline with no calendar
     * attached, so asking Thymeleaf's {@code #temporals.format} for a {@code yyyy-MM-dd}
     * renders it against fields the value does not have and throws at render time — a page
     * that compiles, deploys, and then fails only when a row exists to draw.
     *
     * <p>Giving it a zone is what turns an instant into a date, and UTC is the choice
     * because the column stores UTC and a server's local zone is an accident of where it
     * happens to run. The page says so next to the numbers.</p>
     */
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    public String firstSeen() {
        return firstLoginDate == null ? "—" : DAY.format(firstLoginDate);
    }

    public String lastSeen() {
        return updatedAt == null ? "—" : MINUTE.format(updatedAt);
    }
}
