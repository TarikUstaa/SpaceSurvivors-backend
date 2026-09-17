package com.tarikusta.spacesurvivors.player;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * The rules for a display name, and how a default one is made.
 *
 * <p>These lived as private members of {@link PlayerService} while it was the only place a name
 * was ever written. The backoffice now creates test players too, and a second copy of "3–16
 * characters, letters, digits, underscore" is the kind of duplicate that drifts: the day one copy
 * changes, the other starts writing names the first would refuse. The database's CHECK in
 * V1__init.sql would still catch it, but as an exception rather than a sentence.</p>
 */
public final class DisplayNames {

    /** Always exactly six digits: User100000..User999999, so 900k names to draw from. */
    private static final int NAME_MIN_NUMBER = 100_000;
    private static final int NAME_NUMBER_RANGE = 900_000;

    public static final int MIN = 3;
    public static final int MAX = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private DisplayNames() {
    }

    /**
     * e.g. {@code User104829}. Random rather than sequential, so the name does not leak how
     * many players there are.
     */
    public static String generate() {
        return "User" + (NAME_MIN_NUMBER + RANDOM.nextInt(NAME_NUMBER_RANGE));
    }

    /**
     * What is wrong with a name, or empty if nothing is.
     *
     * <p>A sentence rather than an exception, so each caller decides what a bad name means to
     * it: the API turns it into a 400, the backoffice into a warning on the page.</p>
     */
    public static Optional<String> problemWith(String name) {
        if (name == null || name.length() < MIN || name.length() > MAX) {
            return Optional.of("name must be " + MIN + "-" + MAX + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!allowed) {
                return Optional.of("name may only contain letters, digits and underscore");
            }
        }
        return Optional.empty();
    }
}
