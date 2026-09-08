package com.tarikusta.spacesurvivors.player;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two native queries in {@link PlayerProfileRepository}, against a real Postgres.
 * A mock cannot say anything about {@code ON CONFLICT}, a case-insensitive unique index,
 * or an interval comparison — those are the database's behaviour, not ours.
 */
@DatabaseTest
@Transactional
class PlayerProfileRepositoryTest {

    @Autowired
    private PlayerProfileRepository players;

    @Autowired
    private JdbcClient db;

    /** Any BCrypt-shaped string; these tests are about the constraints, not the hashing. */
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuvwxyz012345678901234567890";

    private static String device() {
        return "test-" + UUID.randomUUID();
    }

    private static String name() {
        return "User" + (100_000 + (int) (Math.random() * 899_999));
    }

    @Test
    @DisplayName("a free device and name are inserted, and the row is readable straight after")
    void insertsWhenNothingIsTaken() {
        String deviceId = device();

        assertThat(players.insertIfFree(deviceId, name(), "127.0.0.1", HASH)).isEqualTo(1);

        // Proves @Modifying(clearAutomatically) did its job: without it this read could be
        // answered from a persistence context that never saw the native insert.
        Optional<PlayerProfile> found = players.findByDeviceId(deviceId);
        assertThat(found).isPresent();
        assertThat(found.get().getPlayerId()).isNotNull();
        assertThat(found.get().getLastIp()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("a second insert for the same device reports 0 instead of raising")
    void reportsRatherThanRaisingOnADuplicateDevice() {
        String deviceId = device();
        players.insertIfFree(deviceId, name(), null, HASH);

        // The whole point: no exception, so the caller's transaction survives and it can
        // decide what to do. A raised violation would abort the transaction outright.
        assertThat(players.insertIfFree(deviceId, name(), null, HASH)).isZero();
    }

    @Test
    @DisplayName("a taken name reports 0, whatever its casing")
    void reportsADuplicateNameCaseInsensitively() {
        players.insertIfFree(device(), "Tarik", null, HASH);

        assertThat(players.insertIfFree(device(), "Tarik", null, HASH)).isZero();
        assertThat(players.insertIfFree(device(), "TARIK", null, HASH)).isZero();
        assertThat(players.insertIfFree(device(), "tArIk", null, HASH)).isZero();
    }

    @Test
    @DisplayName("touch does nothing while the row is fresh")
    void touchIsThrottled() {
        String deviceId = device();
        players.insertIfFree(deviceId, name(), "127.0.0.1", HASH);
        UUID playerId = players.findByDeviceId(deviceId).orElseThrow().getPlayerId();

        players.touch(playerId, "8.8.8.8");

        // Just inserted, so updated_at is seconds old and the five-minute guard holds.
        assertThat(currentIp(playerId)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("touch writes once the row is stale enough")
    void touchWritesAfterTheInterval() {
        String deviceId = device();
        players.insertIfFree(deviceId, name(), "127.0.0.1", HASH);
        UUID playerId = players.findByDeviceId(deviceId).orElseThrow().getPlayerId();

        ageRowPastTheThreshold(playerId);

        players.touch(playerId, "8.8.8.8");

        assertThat(currentIp(playerId)).isEqualTo("8.8.8.8");
    }

    @Test
    @DisplayName("a request with no usable address keeps the last one known")
    void touchDoesNotErasePreviousAddress() {
        String deviceId = device();
        players.insertIfFree(deviceId, name(), "127.0.0.1", HASH);
        UUID playerId = players.findByDeviceId(deviceId).orElseThrow().getPlayerId();
        ageRowPastTheThreshold(playerId);

        players.touch(playerId, null);

        assertThat(currentIp(playerId)).isEqualTo("127.0.0.1");
    }

    /**
     * Make a row look old enough for the throttle to let a write through.
     *
     * <p>The trigger has to come off first: it stamps {@code updated_at = now()} on every
     * update, so it would immediately undo the backdating. That is exactly what it is for
     * — the column cannot be falsified by any code path, this test included — but it means
     * the only honest way to test the interval is to switch the trigger off around the
     * change. The test transaction rolls back, so it goes straight back on.</p>
     */
    private void ageRowPastTheThreshold(UUID playerId) {
        db.sql("ALTER TABLE player_profile DISABLE TRIGGER player_profile_touch_updated_at").update();
        db.sql("UPDATE player_profile SET updated_at = now() - interval '10 minutes' WHERE player_id = :id")
                .param("id", playerId).update();
        db.sql("ALTER TABLE player_profile ENABLE TRIGGER player_profile_touch_updated_at").update();
    }

    private String currentIp(UUID playerId) {
        return db.sql("SELECT host(last_ip) FROM player_profile WHERE player_id = :id")
                .param("id", playerId).query(String.class).single();
    }
}
