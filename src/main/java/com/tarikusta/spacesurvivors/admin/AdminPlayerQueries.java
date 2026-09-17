package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.player.PlayerProfile;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The backoffice's view of the players: reading them, creating a test player, deleting one.
 *
 * <p>Extends {@link Repository} rather than {@code JpaRepository} on purpose: the base
 * interface contributes no methods at all, so this type exposes exactly the query below and
 * no {@code save}, {@code delete} or {@code deleteAll}. The backoffice is a place where a
 * misplaced call does real damage, and the cheapest guard is not having the method.</p>
 *
 * <p>It lives in the admin package, not next to {@code PlayerProfileRepository}, so that the
 * player package keeps serving the game and does not slowly accumulate screens.</p>
 */
public interface AdminPlayerQueries extends Repository<PlayerProfile, UUID> {

    @Query("select count(p) from PlayerProfile p")
    long countPlayers();

    /**
     * Mark the current transaction as not being the player: updates to {@code player_profile}
     * made after this leave {@code updated_at} — "last seen" — where it was. See
     * V7__preserve_last_seen.sql. Transaction-local, so it ends with the caller's transaction.
     */
    @Query(value = "SELECT set_config('app.preserve_updated_at', 'on', true)", nativeQuery = true)
    String preserveLastSeen();

    /**
     * Rename, unless another player already holds the name in any capitalisation.
     *
     * <p>The check and the write are one statement, so the window in which somebody else could
     * take the name between them is the statement itself rather than a round trip. The unique
     * index on {@code lower(display_name)} remains the real guard. A player may change only the
     * case of their own name — {@code o.player_id <> :id} leaves them out of the comparison.</p>
     *
     * @return 1 if renamed, 0 if the name was taken (or there is no such player)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE player_profile SET display_name = :name
             WHERE player_id = :id
               AND NOT EXISTS (SELECT 1 FROM player_profile o
                                WHERE lower(o.display_name) = lower(:name)
                                  AND o.player_id <> :id)
            """, nativeQuery = true)
    int renameIfFree(@Param("id") UUID playerId, @Param("name") String name);

    @Query("select count(g) from PlayerProgress g")
    long countSaves();


    /**
     * One player, with their save if they have one.
     *
     * <p>A {@code LEFT JOIN}, not a join: a player who has authenticated but never finished a
     * run has no {@code player_progress} row at all, and an inner join would answer "no such
     * player" for somebody who plainly exists. The save fields are boxed types for the same
     * reason — they are genuinely absent, not zero.</p>
     */
    @Query("""
            select new com.tarikusta.spacesurvivors.admin.AdminPlayerDetail(
                       p.playerId, p.displayName, p.country, p.lastIp,
                       p.firstLoginDate, p.updatedAt,
                       g.version, g.updatedAt, g.progressData, p.createdByAdmin)
            from PlayerProfile p
            left join PlayerProgress g on g.playerId = p.playerId
            where p.playerId = :playerId
            """)
    Optional<AdminPlayerDetail> findDetail(@Param("playerId") UUID playerId);

    /**
     * Create a test player — the one write in this interface that adds rather than removes.
     *
     * <p>{@code ON CONFLICT DO NOTHING} for the same reason {@code PlayerProfileRepository.insertIfFree}
     * uses it: a taken name returns 0 instead of raising, and a raised unique violation would
     * abort the transaction, so neither a retry with another generated name nor the audit write
     * could run afterwards.</p>
     *
     * <p>{@code created_by_admin} is set here and in no other statement in the application.</p>
     *
     * @param secretHash never null — see V6__admin_test_players.sql for what null would allow
     * @return 1 if the player was created, 0 if the name (or, impossibly, the device id) was taken
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO player_profile (device_id, display_name, device_secret_hash, created_by_admin)
            VALUES (:deviceId, :displayName, :secretHash, true)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertTestPlayer(@Param("deviceId") String deviceId,
                         @Param("displayName") String displayName,
                         @Param("secretHash") String secretHash);

    @Query("select p.playerId from PlayerProfile p where p.deviceId = :deviceId")
    Optional<UUID> findIdByDeviceId(@Param("deviceId") String deviceId);

    @Query("select count(p) from PlayerProfile p where p.createdByAdmin = true")
    long countTestPlayers();

    /**
     * Every test player at once, with their saves and scores (the database's cascade, as for a
     * single delete). Written against the flag alone: nothing a real device can do sets it, so
     * this cannot reach a real player however many there are.
     *
     * @return how many players were removed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PlayerProfile p WHERE p.createdByAdmin = true")
    int deleteTestPlayers();

    /**
     * Delete a player, and with them everything that hangs off them.
     *
     * <p>The cascade is the database's, not JPA's. A bulk JPQL delete does not walk
     * associations — Hibernate issues one {@code DELETE FROM player_profile} and nothing
     * else — so what removes the save and the leaderboard rows is the {@code ON DELETE
     * CASCADE} written on those foreign keys in V1__init.sql. Worth knowing precisely,
     * because it means the rule lives in one place and applies to every writer, including a
     * hand-typed statement in psql.</p>
     *
     * <p>This is the most destructive thing the backoffice can do and there is no undo. The
     * controller makes the caller type the player's name first, and checks it server-side.</p>
     *
     * @return 1 if a player was removed, 0 if there was nothing there
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PlayerProfile p WHERE p.playerId = :playerId")
    int deletePlayer(@Param("playerId") UUID playerId);
}
