package com.tarikusta.spacesurvivors.leaderboard;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Four queries, four different techniques — roughly the whole range Spring Data offers,
 * and a fair picture of when each is the right reach.
 *
 * <p>Extends the bare {@link Repository} marker rather than {@code JpaRepository}, so the
 * only methods that exist are the four below. That is deliberate: a leaderboard row must
 * only ever be written through {@link #saveBest}, which enforces upsert-if-better. An
 * inherited {@code save(entity)} would quietly overwrite a player's best with a worse run,
 * and nothing in the type system would object. A repository should expose the operations
 * the domain allows, not every operation the framework can generate.</p>
 */
public interface LeaderboardEntryRepository
        extends Repository<LeaderboardEntry, LeaderboardEntryId> {

    /**
     * This player's stored best time in a mode.
     *
     * <p>A scalar JPQL projection rather than {@code findByPlayerIdAndMode}: the caller
     * only compares a number, so loading and managing a whole entity to read one field
     * off it would be work for nothing.</p>
     */
    @Query("""
            SELECT e.survivedSeconds FROM LeaderboardEntry e
             WHERE e.playerId = :playerId AND e.mode = :mode
            """)
    Optional<Double> findPersonalBest(@Param("playerId") UUID playerId, @Param("mode") String mode);

    /**
     * The public board. JPQL, not native: the join is expressible and a constructor
     * expression maps straight onto {@link BoardRow}, so no entity is loaded and no
     * column is fetched that the board does not show — the player and device ids never
     * leave the database.
     *
     * <p>{@code JOIN ... ON} across unrelated entities avoids inventing a
     * {@code @ManyToOne} that only one query would use. {@link Pageable} supplies the
     * limit; Hibernate turns it into the dialect's own paging.</p>
     *
     * <p>The tie-break must stay in step with {@link #findStanding} — a player told
     * "rank 2" who then appears third in the list is a bug users notice.</p>
     */
    @Query("""
            SELECT new com.tarikusta.spacesurvivors.leaderboard.BoardRow(
                       p.displayName, e.survivedSeconds, e.kills, e.reachedLevel)
              FROM LeaderboardEntry e
              JOIN PlayerProfile p ON p.playerId = e.playerId
             WHERE e.mode = :mode
             ORDER BY e.survivedSeconds DESC, e.achievedAt ASC
            """)
    List<BoardRow> topEntries(@Param("mode") String mode, Pageable limit);

    /**
     * This player's own time and 1-based rank.
     *
     * <p>Native: the rank is a correlated sub-query in the SELECT list, which JPQL cannot
     * express. Because the outer FROM is the player's own row, a player with no entry
     * yields no row at all — a flat COUNT would have returned rank 1 for someone who is
     * not on the board.</p>
     */
    @Query(value = """
            SELECT me.survived_seconds AS seconds,
                   (SELECT count(*) + 1
                      FROM leaderboard o
                     WHERE o.mode = me.mode
                       AND (o.survived_seconds > me.survived_seconds
                            OR (o.survived_seconds = me.survived_seconds
                                AND o.achieved_at < me.achieved_at))) AS rank
              FROM leaderboard me
             WHERE me.player_id = :playerId AND me.mode = :mode
            """, nativeQuery = true)
    Optional<Standing> findStanding(@Param("playerId") UUID playerId, @Param("mode") String mode);

    /**
     * Write the run as this player's best.
     *
     * <p>Native: {@code ON CONFLICT ... DO UPDATE} is Postgres' upsert and JPA has no
     * equivalent. {@code EXCLUDED} refers to the row we tried to insert, so this copies
     * the new values over the old in one statement instead of read-then-write.</p>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO leaderboard
                (player_id, mode, survived_seconds, kills, reached_level, bosses_defeated)
            VALUES (:playerId, :mode, :seconds, :kills, :reachedLevel, :bossesDefeated)
            ON CONFLICT (player_id, mode) DO UPDATE SET
                survived_seconds = EXCLUDED.survived_seconds,
                kills            = EXCLUDED.kills,
                reached_level    = EXCLUDED.reached_level,
                bosses_defeated  = EXCLUDED.bosses_defeated,
                achieved_at      = now()
            """, nativeQuery = true)
    void saveBest(@Param("playerId") UUID playerId,
                  @Param("mode") String mode,
                  @Param("seconds") double seconds,
                  @Param("kills") int kills,
                  @Param("reachedLevel") int reachedLevel,
                  @Param("bossesDefeated") int bossesDefeated);

    /**
     * Projection for {@link #findStanding}. An interface rather than a record because a
     * native query maps by column name onto accessors; Spring Data implements it.
     */
    interface Standing {
        double getSeconds();

        int getRank();
    }
}
