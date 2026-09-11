package com.tarikusta.spacesurvivors.leaderboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One row of {@code leaderboard}: a player's personal best in one mode. */
@Entity
@Table(name = "leaderboard")
@IdClass(LeaderboardEntryId.class)
public class LeaderboardEntry {

    @Id
    @Column(name = "player_id", nullable = false, updatable = false)
    private UUID playerId;

    @Id
    @Column(nullable = false, updatable = false)
    private String mode;

    @Column(name = "survived_seconds", nullable = false)
    private float survivedSeconds;

    @Column(nullable = false)
    private int kills;

    @Column(name = "reached_level", nullable = false)
    private int reachedLevel;

    @Column(name = "bosses_defeated", nullable = false)
    private int bossesDefeated;

    @Column(name = "achieved_at", insertable = false, updatable = false)
    private Instant achievedAt;

    protected LeaderboardEntry() {
    }


    /**
     * Identity is the primary key, and nothing else.
     *
     * <p>JPA needs these to be stable: the persistence context uses them to recognise an
     * instance it already holds, and a {@code Set} of entities silently misbehaves without
     * them. Comparing mutable fields instead would mean an entity changed identity the
     * moment someone edited it.</p>
     *
     * <p>{@code hashCode} is a constant rather than {@code id.hashCode()} on purpose: it
     * has to stay the same across an entity's whole life, including before an id exists.
     * A collection would otherwise lose an element the instant it was persisted.</p>
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LeaderboardEntry that)) {
            return false;
        }
        return playerId != null
                && Objects.equals(playerId, that.playerId)
                && Objects.equals(mode, that.mode);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getMode() {
        return mode;
    }

    public float getSurvivedSeconds() {
        return survivedSeconds;
    }

    public int getKills() {
        return kills;
    }

    public int getReachedLevel() {
        return reachedLevel;
    }
}
