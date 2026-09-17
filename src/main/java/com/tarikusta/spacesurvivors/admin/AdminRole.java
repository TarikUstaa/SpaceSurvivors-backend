package com.tarikusta.spacesurvivors.admin;

import java.util.Locale;
import java.util.Optional;

/**
 * The kinds of backoffice operator, and the one place their names are spelled.
 *
 * <p>The {@code role} column stores {@link #name()}; V5__admin_roles.sql holds the same list in a
 * CHECK constraint, so a row cannot carry a role this enum does not know about no matter who
 * wrote it.</p>
 *
 * <p><b>What each role may do is not written here.</b> It is written where it is enforced —
 * {@link AdminSecurityConfig} for the URLs and {@code @PreAuthorize} on the service methods — and
 * repeating it in a description on this enum would be a third copy that nothing checks. The
 * users page reads {@link #summary} only to tell a person what they are choosing.</p>
 */
public enum AdminRole {

    ADMIN("Everything, including deleting players, the audit trail and managing accounts."),

    SUPPORT("Players and the leaderboard: view, edit a save, remove a score. "
            + "Cannot delete a player, read the audit trail or manage accounts.");

    /**
     * The expressions {@code @PreAuthorize} uses. Constants rather than string literals at each
     * annotation, because an annotation value that says {@code hasRole('ADMN')} compiles, runs,
     * and refuses everyone — and the typo sits in a place nobody reads twice.
     */
    public static final String IS_ADMIN = "hasRole('ADMIN')";
    public static final String IS_STAFF = "hasAnyRole('ADMIN', 'SUPPORT')";

    private final String summary;

    AdminRole(String summary) {
        this.summary = summary;
    }

    public String summary() {
        return summary;
    }

    /**
     * The authority Spring Security compares against: {@code ROLE_ADMIN}. The prefix is the
     * framework's convention and lives here, not in the database — see V3__admin_user.sql.
     */
    public String authority() {
        return "ROLE_" + name();
    }

    /**
     * Parse a role that arrived from a form.
     *
     * <p>Empty rather than an exception, because an unknown value here is somebody's input and
     * not a bug: the caller turns it into a sentence. {@code Locale.ROOT} for the reason spelled
     * out in {@code AdminBoardService.normalise} — in Turkish, {@code "admin".toUpperCase()} is
     * {@code "ADMİN"}, which matches nothing.</p>
     */
    public static Optional<AdminRole> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String candidate = value.trim().toUpperCase(Locale.ROOT);
        for (AdminRole role : values()) {
            if (role.name().equals(candidate)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
