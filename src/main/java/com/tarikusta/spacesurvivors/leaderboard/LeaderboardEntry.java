package com.tarikusta.spacesurvivors.leaderboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
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
