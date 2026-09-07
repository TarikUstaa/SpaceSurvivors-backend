package com.tarikusta.spacesurvivors.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

/**
 * The {@code player_progress} row, as JPA sees it.
 *
 * <p>Unlike the records elsewhere in this project, an entity is <em>managed</em>: inside
 * a transaction Hibernate tracks it and writes back changes without being asked. That is
 * the difference between a mapped row and a carrier of data.</p>
 */
@Entity
@Table(name = "player_progress")
public class PlayerProgress {

    @Id
    @Column(name = "player_id", nullable = false, updatable = false)
    private UUID playerId;

    /**
     * The whole client save, stored as one JSON object.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} is what lets a plain String map onto a
     * {@code jsonb} column: without it Hibernate would try to bind a varchar and Postgres
     * would refuse. The server still never looks inside — the client owns this shape.</p>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress_data", nullable = false)
    private String progressData;

    /**
     * The optimistic lock, now enforced by Hibernate rather than by hand.
     *
     * <p>Every update becomes {@code ... WHERE player_id = ? AND version = ?} and bumps
     * the column; if no row matches, Hibernate raises
     * {@link jakarta.persistence.OptimisticLockException}. This annotation is the whole
     * mechanism the repository used to implement by counting affected rows.</p>
     */
    @Version
    @Column(nullable = false)
    private int version;

    /** Written by a database trigger, so JPA reads it and never sends it. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private java.time.Instant updatedAt;

    protected PlayerProgress() {
        // JPA needs a no-arg constructor; not for application code to call.
    }

    public PlayerProgress(UUID playerId, String progressData) {
        this.playerId = playerId;
        this.progressData = progressData;
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
        if (!(other instanceof PlayerProgress that)) {
            return false;
        }
        return playerId != null && Objects.equals(playerId, that.playerId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getProgressData() {
        return progressData;
    }

    public void setProgressData(String progressData) {
        this.progressData = progressData;
    }

    public int getVersion() {
        return version;
    }
}
