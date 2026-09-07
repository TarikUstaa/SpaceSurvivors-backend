package com.tarikusta.spacesurvivors.leaderboard;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The composite key of {@code leaderboard}: one row per player per mode.
 *
 * <p>JPA needs a separate class whenever a primary key spans more than one column, and it
 * must be {@link Serializable} with {@code equals} and {@code hashCode} — those are how
 * the persistence context recognises an entity it has already loaded. Getting them wrong
 * shows up as duplicate loads or lost updates rather than as a compile error.</p>
 *
 * <p>Declared with {@code @IdClass} rather than {@code @EmbeddedId} so the entity keeps
 * {@code playerId} and {@code mode} as ordinary fields and queries read naturally.</p>
 */
public class LeaderboardEntryId implements Serializable {

    private UUID playerId;
    private String mode;

    protected LeaderboardEntryId() {
    }

    public LeaderboardEntryId(UUID playerId, String mode) {
        this.playerId = playerId;
        this.mode = mode;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LeaderboardEntryId that)) return false;
        return Objects.equals(playerId, that.playerId) && Objects.equals(mode, that.mode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, mode);
    }
}
