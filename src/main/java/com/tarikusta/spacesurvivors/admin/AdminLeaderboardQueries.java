package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.leaderboard.LeaderboardEntry;
import com.tarikusta.spacesurvivors.leaderboard.LeaderboardEntryId;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * What the backoffice may do to the leaderboard: read a whole mode, set one row, remove one row.
 *
 * <p>The bare {@link Repository} marker again, for the same reason
 * {@code LeaderboardEntryRepository} uses it: inheriting {@code JpaRepository} would hand the
 * backoffice a generic {@code save} and {@code deleteAll}. Every write here is a named statement
 * with one purpose.</p>
 *
 * <p>This interface used to say an administrator should be able to take a row away and never
 * invent one. That changed on purpose on 2026-09-17 ({@link #setEntry}): testing the board with a
 * single real account is not testing it. The invented rows are kept honest another way — only
 * ADMIN may write them, every write is audited with the value it replaced, and the same
 * plausibility rule the game's submissions face still applies.</p>
 */
public interface AdminLeaderboardQueries extends Repository<LeaderboardEntry, LeaderboardEntryId> {

    /**
     * Every row in a mode, ranked the way the game ranks them.
     *
     * <p>The ordering is copied from the public board's query on purpose, tie-break
     * included: an administrator looking for the row a player is complaining about should be
     * counting down the same list the player is looking at.</p>
     *
     * <p>Unpaged — at this size a mode is a handful of rows. The public board caps at 100 and
     * this does not, which is the right way round: the cap there protects the service from a
     * large response, while here the whole point is to see everything, including the row a
     * top-100 limit would hide.</p>
     */
    @Query("""
            SELECT new com.tarikusta.spacesurvivors.admin.AdminBoardRow(
                       e.playerId, p.displayName, e.mode, e.survivedSeconds, e.kills,
                       e.reachedLevel, e.bossesDefeated, e.achievedAt)
              FROM LeaderboardEntry e
              JOIN PlayerProfile p ON p.playerId = e.playerId
             WHERE e.mode = :mode
             ORDER BY e.survivedSeconds DESC, e.achievedAt ASC
            """)
    List<AdminBoardRow> listByMode(@Param("mode") String mode);

    /** This player's rows, at most one per mode — what the player detail page shows. */
    @Query("""
            SELECT new com.tarikusta.spacesurvivors.admin.AdminBoardRow(
                       e.playerId, p.displayName, e.mode, e.survivedSeconds, e.kills,
                       e.reachedLevel, e.bossesDefeated, e.achievedAt)
              FROM LeaderboardEntry e
              JOIN PlayerProfile p ON p.playerId = e.playerId
             WHERE e.playerId = :playerId
             ORDER BY e.mode
            """)
    List<AdminBoardRow> listByPlayer(@Param("playerId") UUID playerId);

    /**
     * Set one player's score in one mode, replacing whatever was there — higher or lower.
     *
     * <p>Unlike the game's {@code saveBest}, which is only called when a run beats the stored
     * best, this writes unconditionally: an operator setting up a test board needs to be able to
     * put a score <em>down</em> as well as up.</p>
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
    void setEntry(@Param("playerId") UUID playerId,
                  @Param("mode") String mode,
                  @Param("seconds") double seconds,
                  @Param("kills") int kills,
                  @Param("reachedLevel") int reachedLevel,
                  @Param("bossesDefeated") int bossesDefeated);

    /**
     * Take one score off the board.
     *
     * <p>Only the leaderboard row goes; the player keeps their account and their save. That
     * is the difference between "this score should not be there" and "this person should not
     * be here", and conflating them is how a moderation tool ends up deleting a save because
     * somebody cheated once.</p>
     *
     * <p>Both halves of the key are required — a player has one row per mode, and a delete
     * by player id alone would quietly take the other mode with it.</p>
     *
     * @return the number of rows removed: 1 normally, 0 if it was already gone, which the
     *         caller reports rather than pretending the removal happened
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM LeaderboardEntry e WHERE e.playerId = :playerId AND e.mode = :mode")
    int deleteEntry(@Param("playerId") UUID playerId, @Param("mode") String mode);
}
