package com.tarikusta.spacesurvivors.player;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Every SQL statement that touches {@code player_profile}. */
@Repository
public class PlayerRepository {

    /** "Last seen" only needs to be roughly right; see {@link #touch}. */
    private static final String TOUCH_INTERVAL = "5 minutes";

    private final JdbcClient db;

    public PlayerRepository(JdbcClient db) {
        this.db = db;
    }

    /** The player this device belongs to, or empty if the device has never been seen. */
    public Optional<UUID> findIdByDevice(String deviceId) {
        return db.sql("SELECT player_id FROM player_profile WHERE device_id = :device")
                .param("device", deviceId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Create the player, or report that something was already taken.
     *
     * <p>{@code ON CONFLICT DO NOTHING} covers <em>both</em> unique constraints — the
     * device and the lowercased name — and, crucially, returns no rows instead of
     * raising. A raised constraint violation would abort the surrounding transaction
     * in Postgres, and every later statement in it (including any attempt to recover)
     * would then fail with {@code 25P02}. Not raising is what makes the caller's
     * retry possible at all.
     *
     * @return the new player, or empty if the device or the name was taken
     */
    public Optional<UUID> insertIfFree(String deviceId, String displayName, String ip) {
        return db.sql("""
                        INSERT INTO player_profile (device_id, display_name, last_ip)
                        VALUES (:device, :name, CAST(:ip AS inet))
                        ON CONFLICT DO NOTHING
                        RETURNING player_id
                        """)
                .param("device", deviceId)
                .param("name", displayName)
                .param("ip", ip)
                .query(UUID.class)
                .optional();
    }

    /**
     * Record that we just saw this player — at most once every {@value #TOUCH_INTERVAL}.
     *
     * <p>This runs on every authenticated request, reads included, so writing every time
     * would mean a row update and a WAL record per API call for a value nobody needs to
     * the second. {@code COALESCE} keeps the last known address when the current request
     * has no usable one.</p>
     */
    public void touch(UUID playerId, String ip) {
        db.sql("""
                        UPDATE player_profile
                           SET last_ip = COALESCE(CAST(:ip AS inet), last_ip)
                         WHERE player_id = :id
                           AND updated_at < now() - CAST(:interval AS interval)
                        """)
                .param("id", playerId)
                .param("ip", ip)
                .param("interval", TOUCH_INTERVAL)
                .update();
    }

    /**
     * Change the display name.
     *
     * <p>No "is this name free?" check first: between the check and the write another
     * request could take it. The unique index is the real guard, so we simply write and
     * let the constraint reject a clash.</p>
     *
     * @throws org.springframework.dao.DuplicateKeyException if the name is already taken
     * @return true if the player exists and was renamed
     */
    public boolean rename(UUID playerId, String displayName) {
        return db.sql("UPDATE player_profile SET display_name = :name WHERE player_id = :id")
                .param("id", playerId)
                .param("name", displayName)
                .update() == 1;
    }

    public Optional<Player> find(UUID playerId) {
        return db.sql("""
                        SELECT player_id, device_id, display_name, country
                          FROM player_profile
                         WHERE player_id = :id
                        """)
                .param("id", playerId)
                .query((rs, rowNum) -> new Player(
                        rs.getObject("player_id", UUID.class),
                        rs.getString("device_id"),
                        rs.getString("display_name"),
                        rs.getString("country")))
                .optional();
    }
}
