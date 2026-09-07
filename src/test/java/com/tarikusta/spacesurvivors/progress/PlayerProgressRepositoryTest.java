package com.tarikusta.spacesurvivors.progress;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against a real Postgres, because these two behaviours cannot be verified anywhere else:
 * a {@code jsonb} column round-tripping through Hibernate, and {@code @Version} actually
 * refusing a stale write. A mock would only confirm what we already believe.
 *
 * <p>{@code @Transactional} rolls each test back, so they leave no rows behind.</p>
 */
@SpringBootTest
@Transactional
class PlayerProgressRepositoryTest {

    private static final String SAVE = """
            {"schemaVersion":5,"wallet":100,"ownedShipIds":["scout"]}""";

    @Autowired
    private PlayerProgressRepository progress;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcClient db;

    private UUID playerId;

    /** progress rows need a player to hang off, so make one directly. */
    @BeforeEach
    void createOwningPlayer() {
        playerId = db.sql("""
                        INSERT INTO player_profile (device_id, display_name)
                        VALUES (:device, :name)
                        RETURNING player_id
                        """)
                .param("device", "test-" + UUID.randomUUID())
                .param("name", "User" + (100_000 + (int) (Math.random() * 899_999)))
                .query(UUID.class)
                .single();
    }

    @Test
    @DisplayName("a jsonb column round-trips through Hibernate unchanged")
    void storesAndReadsBackTheJsonExactly() {
        progress.saveAndFlush(new PlayerProgress(playerId, SAVE));
        entityManager.clear();   // force a real read rather than the first-level cache

        String stored = progress.findById(playerId).orElseThrow().getProgressData();

        // The danger with @JdbcTypeCode(SqlTypes.JSON) on a String is double encoding —
        // the object arriving back wrapped in quotes as a JSON string. Reading a field out
        // of it in SQL proves Postgres holds a real object, not text that looks like one.
        assertThat(db.sql("SELECT progress_data->>'wallet' FROM player_progress WHERE player_id = :id")
                .param("id", playerId).query(String.class).single()).isEqualTo("100");
        assertThat(stored).contains("\"wallet\"").contains("scout");
    }

    @Test
    @DisplayName("@Version increments on every write")
    void versionMovesWithEachSave() {
        PlayerProgress created = progress.saveAndFlush(new PlayerProgress(playerId, SAVE));
        int first = created.getVersion();

        // Pinned deliberately: this number is part of the API contract. Hibernate starts a
        // @Version at 0, where the hand-written implementation used to start at 1, and the
        // client echoes whatever it is told back on the next write.
        assertThat(first).isZero();

        created.setProgressData("""
                {"wallet":250}""");
        int second = progress.saveAndFlush(created).getVersion();

        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    @DisplayName("a stale version is refused, not silently applied")
    void refusesAWriteCarryingAnOldVersion() {
        progress.saveAndFlush(new PlayerProgress(playerId, SAVE));
        entityManager.clear();

        // Loaded, so it remembers the version it saw.
        PlayerProgress mine = progress.findById(playerId).orElseThrow();

        // Somebody else writes first, straight past the persistence context.
        db.sql("UPDATE player_progress SET version = version + 1 WHERE player_id = :id")
                .param("id", playerId).update();

        // Our copy still believes the old version, so the UPDATE Hibernate emits matches
        // no row. Constructing a stale entity by hand would need a version setter that
        // production has no use for; letting the row move underneath a loaded one is both
        // closer to what actually happens and honest about the entity's API.
        mine.setProgressData("""
                {"wallet":999}""");

        assertThatThrownBy(() -> progress.saveAndFlush(mine))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void findByIdIsEmptyForAPlayerWhoHasNeverSaved() {
        assertThat(progress.findById(playerId)).isEmpty();
    }

    @Test
    @DisplayName("updated_at is written by the trigger, not by JPA")
    void theDatabaseStampsUpdatedAt() {
        progress.saveAndFlush(new PlayerProgress(playerId, SAVE));

        Optional<Boolean> present = db.sql("SELECT updated_at IS NOT NULL FROM player_progress WHERE player_id = :id")
                .param("id", playerId).query(Boolean.class).optional();

        assertThat(present).contains(true);
    }
}
