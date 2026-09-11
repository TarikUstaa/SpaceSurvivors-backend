package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.player.PlayerProfile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;
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
}
