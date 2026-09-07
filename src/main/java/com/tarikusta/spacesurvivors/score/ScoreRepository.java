package com.tarikusta.spacesurvivors.score;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every SQL statement that touches {@code leaderboard_entries}.
 *
 * <p>The table holds ONE row per (player, mode) — that player's personal best — not a
 * history of every run. So "submit a score" is an upsert, and "the board" is just
 * the table ordered by time survived.
 */
@Repository
public class ScoreRepository {

    private final JdbcClient db;

    public ScoreRepository(JdbcClient db) {
        this.db = db;
    }

    /** This player's stored best survival time in a mode, or empty if they have none. */
    public Optional<Double> personalBest(UUID playerId, String mode) {
        return db.sql("""
                        SELECT survived_seconds FROM leaderboard_entries
                        WHERE player_id = :id AND mode = :mode
                        """)
                .param("id", playerId)
                .param("mode", mode)
                .query(Double.class)
                .optional();
    }

    /**
     * Write the run as this player's best for the mode.
     *
     * <p>{@code ON CONFLICT (player_id, mode) DO UPDATE} is Postgres' upsert: insert if
     * this is their first entry, otherwise overwrite the existing one. {@code EXCLUDED}
     * refers to the row we <em>tried</em> to insert, so this copies the new values over
     * the old. The caller only reaches here when the run actually beat the old best.
     */
    public void saveBest(UUID playerId, String mode, double seconds, int kills,
                         int reachedLevel, int bossesDefeated) {
        db.sql("""
                        INSERT INTO leaderboard_entries
                            (player_id, mode, survived_seconds, kills, reached_level, bosses_defeated)
                        VALUES (:id, :mode, :seconds, :kills, :level, :bosses)
                        ON CONFLICT (player_id, mode) DO UPDATE SET
                            survived_seconds = EXCLUDED.survived_seconds,
                            kills            = EXCLUDED.kills,
                            reached_level    = EXCLUDED.reached_level,
                            bosses_defeated  = EXCLUDED.bosses_defeated,
                            achieved_at      = now()
                        """)
                .param("id", playerId)
                .param("mode", mode)
                .param("seconds", seconds)
                .param("kills", kills)
                .param("level", reachedLevel)
                .param("bosses", bossesDefeated)
                .update();
    }

    /**
     * This player's own standing: their time plus their 1-based rank.
     *
     * <p>The rank is a correlated sub-query — "how many entries beat mine, plus one".
     * Because the outer {@code FROM} is the player's own row, the whole query returns
     * zero rows when they have no entry, which maps cleanly to {@link Optional#empty()}.
     * (Counting in a flat query would have returned rank 1 for a player with no entry.)
     *
     * <p>The "beats me" test must stay in step with the ORDER BY in {@link #top} —
     * longer survival wins, and an equal time is beaten by whoever got there first.
     */
    public Optional<ScoreDtos.Me> findMe(UUID playerId, String mode) {
        return db.sql("""
                        SELECT me.survived_seconds,
                               (SELECT count(*) + 1
                                  FROM leaderboard_entries o
                                 WHERE o.mode = me.mode
                                   AND (o.survived_seconds > me.survived_seconds
                                        OR (o.survived_seconds = me.survived_seconds
                                            AND o.achieved_at < me.achieved_at))) AS rank
                          FROM leaderboard_entries me
                         WHERE me.player_id = :id AND me.mode = :mode
                        """)
                .param("id", playerId)
                .param("mode", mode)
                .query((rs, rowNum) -> new ScoreDtos.Me(rs.getInt("rank"), rs.getDouble("survived_seconds")))
                .optional();
    }

    /**
     * Top {@code limit} entries of a mode, best first. Joined to {@code player_profile} so the
     * board shows display names — no id is ever exposed publicly.
     *
     * <p>Rank is not selected here: the rows come back already ordered, so the service
     * numbers them 1..n by position. That matches {@link #findMe} because both use the
     * same tie-break.
     */
    public List<BoardRow> top(String mode, int limit) {
        return db.sql("""
                        SELECT p.display_name, e.survived_seconds, e.kills, e.reached_level
                          FROM leaderboard_entries e
                          JOIN player_profile p ON p.player_id = e.player_id
                         WHERE e.mode = :mode
                         ORDER BY e.survived_seconds DESC, e.achieved_at ASC
                         LIMIT :limit
                        """)
                .param("mode", mode)
                .param("limit", limit)
                .query((rs, rowNum) -> new BoardRow(
                        rs.getString("display_name"),
                        rs.getDouble("survived_seconds"),
                        rs.getInt("kills"),
                        rs.getInt("reached_level")))
                .list();
    }

    /** A board row straight out of SQL, before the service stamps a rank on it. */
    public record BoardRow(String displayName, double survivedSeconds, int kills, int reachedLevel) {
    }
}
