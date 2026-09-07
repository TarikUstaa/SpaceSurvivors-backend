package com.tarikusta.spacesurvivors.progress;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Every SQL statement that touches {@code player_progress}. */
@Repository
public class ProgressRepository {

    private final JdbcClient db;

    public ProgressRepository(JdbcClient db) {
        this.db = db;
    }

    /** The stored save and its version, or empty if this player has never saved. */
    public Optional<StoredProgress> find(UUID playerId) {
        return db.sql("""
                        SELECT progress_data::text AS progress_data, version
                          FROM player_progress
                         WHERE player_id = :id
                        """)
                .param("id", playerId)
                .query((rs, rowNum) -> new StoredProgress(rs.getString("progress_data"), rs.getInt("version")))
                .optional();
    }

    /** First save for this player: insert at version 1. */
    public void insert(UUID playerId, String progressJson) {
        db.sql("""
                        INSERT INTO player_progress (player_id, progress_data, version)
                        VALUES (:id, CAST(:data AS jsonb), 1)
                        """)
                .param("id", playerId)
                .param("data", progressJson)
                .update();
    }

    /**
     * Optimistic-locked update: writes only while the row is still at
     * {@code expectedVersion}. The {@code AND version = :v} <em>is</em> the lock — if
     * another device already bumped it, no row matches and nothing is overwritten.
     *
     * @return the new version, or empty if the version had moved on
     */
    public Optional<Integer> update(UUID playerId, String progressJson, int expectedVersion) {
        int rowsChanged = db.sql("""
                        UPDATE player_progress
                           SET progress_data = CAST(:data AS jsonb), version = version + 1
                         WHERE player_id = :id AND version = :v
                        """)
                .param("id", playerId)
                .param("data", progressJson)
                .param("v", expectedVersion)
                .update();
        return rowsChanged == 1 ? Optional.of(expectedVersion + 1) : Optional.empty();
    }

    /** Held as text: the raw JSON goes straight back to the client without being parsed. */
    public record StoredProgress(String json, int version) {
    }
}
