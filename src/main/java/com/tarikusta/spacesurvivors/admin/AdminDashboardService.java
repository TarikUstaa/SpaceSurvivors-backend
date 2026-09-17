package com.tarikusta.spacesurvivors.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The numbers on the overview page.
 *
 * <p><b>Test players are left out of every figure</b> and counted on their own. A dashboard that
 * says "12 new players this week" when eleven of them were made up an hour ago for a leaderboard
 * test is worse than no dashboard.</p>
 *
 * <p><b>What "active" means here, precisely:</b> {@code player_profile.updated_at} within the
 * window. It moves when a device signs in (at most once per five minutes) and deliberately not
 * when an operator changes the row (V7). It does not move during a session that only reads, so a
 * player who signed in once and played for three hours counts once, at the start.</p>
 *
 * <p>What it cannot say: how many runs were played, or for how long. The server keeps each
 * player's best score and current save, not a history of runs — the append-only run table was
 * deferred on purpose (D12), and anything here that looked like "runs this week" would be
 * invented.</p>
 *
 * <p>Plain SQL through {@link JdbcClient}: every number is an aggregate, none of them is an
 * entity, and {@code count(*) FILTER (WHERE …)} says in one statement what JPQL would need several
 * for.</p>
 */
@Service
@PreAuthorize(AdminRole.IS_STAFF)
public class AdminDashboardService {

    static final int SIGNUP_DAYS = 14;
    static final int TOP_COUNTRIES = 8;

    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("d MMM", Locale.ROOT);

    public record Players(long real, long newWeek, long activeDay, long activeWeek,
                          long withSave, long test, long suspended) {
    }

    /**
     * One day of the signup chart.
     *
     * @param percent this day's height against the busiest day in the window, 0–100
     */
    public record Day(LocalDate date, long count, int percent) {

        public String label() {
            return DAY_LABEL.format(date);
        }
    }

    /** @param percent this row's width against the largest country, 0–100 */
    public record Country(String code, long count, int percent) {
    }

    public record Mode(String mode, long entries, double bestSeconds) {

        public String best() {
            long s = Math.round(bestSeconds);
            return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
        }
    }

    public record Security(long failedSignIns, long actions) {
    }

    public record Dashboard(Players players, List<Day> signups, long signupsInWindow,
                            List<Country> countries, List<Mode> modes, long testScores,
                            Security security) {
    }

    private final JdbcClient db;

    public AdminDashboardService(JdbcClient db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        List<Day> signups = signups();
        return new Dashboard(players(), signups,
                signups.stream().mapToLong(Day::count).sum(),
                countries(), modes(), testScores(), security());
    }

    private Players players() {
        return db.sql("""
                        SELECT count(*) FILTER (WHERE NOT created_by_admin)                                            AS real,
                               count(*) FILTER (WHERE NOT created_by_admin AND first_login_date > now() - interval '7 days') AS new_week,
                               count(*) FILTER (WHERE NOT created_by_admin AND updated_at > now() - interval '1 day')        AS active_day,
                               count(*) FILTER (WHERE NOT created_by_admin AND updated_at > now() - interval '7 days')       AS active_week,
                               count(*) FILTER (WHERE NOT created_by_admin AND EXISTS
                                               (SELECT 1 FROM player_progress g WHERE g.player_id = p.player_id))  AS with_save,
                               count(*) FILTER (WHERE created_by_admin)                                                AS test,
                               count(*) FILTER (WHERE NOT created_by_admin AND suspended_at IS NOT NULL)               AS suspended
                          FROM player_profile p""")
                .query((rs, n) -> new Players(rs.getLong("real"), rs.getLong("new_week"),
                        rs.getLong("active_day"), rs.getLong("active_week"),
                        rs.getLong("with_save"), rs.getLong("test"), rs.getLong("suspended")))
                .single();
    }

    /**
     * Every day in the window, including the empty ones. Days are UTC calendar days — the same
     * clock every other time on these pages is shown in — and a day with no signups is a zero
     * column rather than a missing one, so a gap in the chart is a gap in players, not in data.
     */
    private List<Day> signups() {
        record Raw(LocalDate date, long count) {
        }
        List<Raw> raw = db.sql("""
                        SELECT d::date AS day, count(p.player_id) AS n
                          FROM generate_series((now() AT TIME ZONE 'UTC')::date - :back,
                                               (now() AT TIME ZONE 'UTC')::date,
                                               interval '1 day') AS d
                          LEFT JOIN player_profile p
                                 ON (p.first_login_date AT TIME ZONE 'UTC')::date = d::date
                                AND NOT p.created_by_admin
                         GROUP BY d
                         ORDER BY d""")
                .param("back", SIGNUP_DAYS - 1)
                .query((rs, n) -> new Raw(rs.getObject("day", LocalDate.class), rs.getLong("n")))
                .list();

        long busiest = raw.stream().mapToLong(Raw::count).max().orElse(0);
        return raw.stream()
                .map(r -> new Day(r.date(), r.count(), percentOf(r.count(), busiest)))
                .toList();
    }

    private List<Country> countries() {
        record Raw(String code, long count) {
        }
        List<Raw> raw = db.sql("""
                        SELECT country, count(*) AS n
                          FROM player_profile
                         WHERE NOT created_by_admin
                         GROUP BY country
                         ORDER BY n DESC, country NULLS LAST
                         LIMIT :top""")
                .param("top", TOP_COUNTRIES)
                .query((rs, n) -> new Raw(rs.getString("country"), rs.getLong("n")))
                .list();

        long largest = raw.stream().mapToLong(Raw::count).max().orElse(0);
        return raw.stream()
                .map(r -> new Country(r.code() == null ? "Unknown" : r.code(), r.count(),
                        percentOf(r.count(), largest)))
                .toList();
    }

    private List<Mode> modes() {
        return db.sql("""
                        SELECT l.mode, count(*) AS entries, max(l.survived_seconds) AS best
                          FROM leaderboard l
                          JOIN player_profile p USING (player_id)
                         WHERE NOT p.created_by_admin
                         GROUP BY l.mode
                         ORDER BY l.mode""")
                .query((rs, n) -> new Mode(rs.getString("mode"), rs.getLong("entries"),
                        rs.getDouble("best")))
                .list();
    }

    private long testScores() {
        return db.sql("""
                        SELECT count(*) FROM leaderboard l
                          JOIN player_profile p USING (player_id)
                         WHERE p.created_by_admin""")
                .query(Long.class).single();
    }

    /** Sign-ins themselves are not "actions": they are the baseline, not something done. */
    private Security security() {
        return db.sql("""
                        SELECT count(*) FILTER (WHERE action = 'SIGN_IN_FAILED')                    AS failed,
                               count(*) FILTER (WHERE action NOT IN ('SIGN_IN_FAILED', 'SIGNED_IN')) AS actions
                          FROM admin_audit
                         WHERE happened_at > now() - interval '1 day'""")
                .query((rs, n) -> new Security(rs.getLong("failed"), rs.getLong("actions")))
                .single();
    }

    /**
     * A bar's length. A non-zero value never rounds down to an invisible 0% bar — a day with one
     * signup next to a day with two hundred still shows a sliver.
     */
    static int percentOf(long value, long max) {
        if (value <= 0 || max <= 0) {
            return 0;
        }
        return (int) Math.max(2, Math.round(100.0 * value / max));
    }
}
