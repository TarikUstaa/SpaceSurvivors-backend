package com.tarikusta.spacesurvivors.game;

import java.util.Locale;
import java.util.Optional;

/**
 * The one message shown on the game's main menu, if there is one.
 *
 * @param level how the game draws it: {@code info} (a notice) or {@code warning} (maintenance, an
 *              outage) — a closed pair, so the client never has to guess what an unknown level means
 */
public record Announcement(String message, String level) {

    public static final String SETTING_KEY = "announcement";
    public static final int MAX_LENGTH = 280;

    public static final String INFO = "info";
    public static final String WARNING = "warning";

    /** The normalised level, or empty if it is not one of the two. */
    public static Optional<String> parseLevel(String value) {
        String candidate = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return INFO.equals(candidate) || WARNING.equals(candidate)
                ? Optional.of(candidate) : Optional.empty();
    }
}
