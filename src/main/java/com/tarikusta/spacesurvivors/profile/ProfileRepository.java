package com.tarikusta.spacesurvivors.profile;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Every SQL statement that touches the {@code players} table lives here. */
@Repository
public class ProfileRepository {

    private final JdbcClient db;

    public ProfileRepository(JdbcClient db) {
        this.db = db;
    }

    /** The stored profile + its version, or empty if this user has never saved. */
    public Optional<StoredProfile> find(String userId) {
        return db.sql("SELECT profile::text AS profile, version FROM players WHERE user_id = :id")
                .param("id", userId)
                .query((rs, rowNum) -> new StoredProfile(rs.getString("profile"), rs.getInt("version")))
                .optional();
    }

    /** First save for this user: insert at version 1. */
    public void insert(String userId, String profileJson) {
        db.sql("INSERT INTO players (user_id, profile, version) VALUES (:id, CAST(:p AS jsonb), 1)")
                .param("id", userId)
                .param("p", profileJson)
                .update();
    }

    /**
     * Optimistic-locked update: writes only if the row is still at
     * {@code expectedVersion}. The {@code WHERE ... AND version = :v} is the lock —
     * if someone else already bumped the version, 0 rows match.
     *
     * @return the new version if it wrote; empty if the version had moved on
     */
    public Optional<Integer> update(String userId, String profileJson, int expectedVersion) {
        int rowsChanged = db.sql("""
                UPDATE players
                SET profile = CAST(:p AS jsonb), version = version + 1
                WHERE user_id = :id AND version = :v
                """)
                .param("id", userId)
                .param("p", profileJson)
                .param("v", expectedVersion)
                .update();
        return rowsChanged == 1 ? Optional.of(expectedVersion + 1) : Optional.empty();
    }

    /** profile stored as text (we hand the raw JSON straight back to the client). */
    public record StoredProfile(String json, int version) {
    }
}
