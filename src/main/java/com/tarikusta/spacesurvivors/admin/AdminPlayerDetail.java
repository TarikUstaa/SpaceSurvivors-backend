package com.tarikusta.spacesurvivors.admin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Everything the backoffice shows about one player.
 *
 * <p>The save comes along because this page is where a "my progress is wrong" report gets
 * answered, and the answer is almost always in the JSON. The server still does not interpret
 * it — the client owns that shape and always has — so it is shown as it is stored rather than
 * unpacked into fields that would go stale the first time the game adds one.</p>
 *
 * <p>{@code deviceId} is deliberately not here. It is the public half of the player's
 * credential and nothing on this page needs it; leaving it out means a screenshot of this
 * page is not half of somebody's account.</p>
 *
 * @param lastIp personal data, which is why it appears here and nowhere the player can see.
 *               It earns its place on this one screen because "is this the same person as
 *               that account" is the question an administrator actually has.
 */
public record AdminPlayerDetail(
        UUID playerId,
        String displayName,
        String country,
        String lastIp,
        Instant firstLoginDate,
        Instant updatedAt,
        Integer saveVersion,
        Instant saveUpdatedAt,
        String saveJson) {

    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneOffset.UTC).withLocale(Locale.ROOT);

    public boolean hasSave() {
        return saveJson != null;
    }

    public String firstSeen() {
        return firstLoginDate == null ? "—" : MINUTE.format(firstLoginDate);
    }

    public String lastSeen() {
        return updatedAt == null ? "—" : MINUTE.format(updatedAt);
    }

    public String savedOn() {
        return saveUpdatedAt == null ? "—" : MINUTE.format(saveUpdatedAt);
    }

    /** Rough, and labelled as such on the page — it is a shape check, not an accounting. */
    public String saveSize() {
        return saveJson == null ? "—" : (saveJson.length() / 1024 + 1) + " KB";
    }
}
