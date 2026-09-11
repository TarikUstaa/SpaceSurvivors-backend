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
 * The backoffice's read-only view of the players.
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

    /**
     * Every player, newest first, with the two counts the list shows.
     *
     * <p>The counts are correlated subqueries rather than joins: a join to
     * {@code leaderboard} would multiply the profile row once per mode and need a
     * {@code group by} to put it back together, and a second join to {@code player_progress}
     * would multiply it again. Two scalar subqueries say what is meant and the planner turns
     * them into the same work.</p>
     *
     * <p>Unpaged, which is honest for now and wrong later — the same open item the public
     * leaderboard has. At a few dozen players it is one small query; the day this list does
     * not fit on a screen it needs a page parameter, and so does this method.</p>
     */
    @Query("""
            select new com.tarikusta.spacesurvivors.admin.AdminPlayerRow(
                       p.playerId, p.displayName, p.country, p.firstLoginDate, p.updatedAt,
                       (select count(l) from LeaderboardEntry l where l.playerId = p.playerId),
                       (select count(g) from PlayerProgress g where g.playerId = p.playerId))
            from PlayerProfile p
            order by p.firstLoginDate desc
            """)
    List<AdminPlayerRow> listAll();

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
                       g.version, g.updatedAt, g.progressData)
            from PlayerProfile p
            left join PlayerProgress g on g.playerId = p.playerId
            where p.playerId = :playerId
            """)
    Optional<AdminPlayerDetail> findDetail(@Param("playerId") UUID playerId);

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
