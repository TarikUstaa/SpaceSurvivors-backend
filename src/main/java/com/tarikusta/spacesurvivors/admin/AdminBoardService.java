package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.exception.NotFoundException;
import com.tarikusta.spacesurvivors.leaderboard.LeaderboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What the backoffice may do to the leaderboard.
 *
 * <p>Named for the board rather than the screen, because two of the three methods here are
 * not about a screen at all. {@link #normalise} is the rule that an unrecognised mode falls
 * back instead of failing, and {@link #removeScore} is the rule that a removal names exactly
 * one row and reports honestly when it found none. Both were in the controller.</p>
 */
@Service
public class AdminBoardService {

    private static final Logger log = LoggerFactory.getLogger(AdminBoardService.class);

    private static final String DEFAULT_MODE = "infinite";

    /** A day. No run lasts that long; past it the number is a typo. */
    private static final int MAX_SECONDS = 24 * 60 * 60;
    private static final int MAX_LEVEL = 10_000;
    private static final int MAX_BOSSES = 10_000;

    /**
     * @param problem set only when the score was refused; the sentence the page shows
     */
    public record ScoreResult(boolean written, String problem) {

        static ScoreResult refused(String problem) {
            return new ScoreResult(false, problem);
        }
    }

    private final AdminLeaderboardQueries queries;
    private final AdminPlayerQueries players;
    private final AdminAudit audit;

    public AdminBoardService(AdminLeaderboardQueries queries, AdminPlayerQueries players,
                             AdminAudit audit) {
        this.queries = queries;
        this.players = players;
        this.audit = audit;
    }

    /**
     * Turn whatever arrived into a mode that exists.
     *
     * <p>The value reaches the application from a query string, so it can be anything at all —
     * a typo, an empty string, a fragment of SQL. Falling back beats failing: a page an
     * administrator can break by mistyping a URL is a page that will be broken.</p>
     *
     * <p>Checked against {@link LeaderboardService#MODES}, the same set the game's own endpoint
     * validates against, so the two cannot drift into disagreeing about what a mode is.</p>
     *
     * <p>{@code Locale.ROOT} is not decoration. {@code toLowerCase()} uses the JVM's default
     * locale, and in Turkish {@code 'I'} lowercases to the dotless {@code 'ı'} — so on a machine
     * set to Turkish, {@code "INFINITE"} becomes {@code "ınfınıte"} and matches nothing. The
     * same code would then behave differently on this laptop and in the container, which is the
     * worst kind of bug to be handed. {@code LeaderboardService} already got this right; this
     * copy did not.</p>
     */
    public String normalise(String mode) {
        String candidate = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return LeaderboardService.MODES.contains(candidate) ? candidate : DEFAULT_MODE;
    }

    /** The modes a person may switch between, in a stable order. */
    public List<String> modes() {
        return LeaderboardService.MODES.stream().sorted().toList();
    }

    @Transactional(readOnly = true)
    public List<AdminBoardRow> rows(String mode) {
        return queries.listByMode(mode);
    }

    /** Every score one player holds, for their detail page. */
    @Transactional(readOnly = true)
    public List<AdminBoardRow> scoresOf(UUID playerId) {
        return queries.listByPlayer(playerId);
    }

    /**
     * Set one player's score in one mode by hand, replacing whatever was there.
     *
     * <p><b>The mode is not normalised the way the page's filter is.</b> {@link #normalise} falls
     * back to a default because a mistyped URL should still show a board; a write that falls back
     * would put a score in a mode nobody chose. An unknown mode is refused.</p>
     *
     * <p>The kill rate goes through {@link LeaderboardService#isPlausible}, the same rule the
     * game's own submissions face. A test board full of scores no client could send would test a
     * board that cannot exist.</p>
     *
     * <p>Time is accepted as seconds ({@code 500}) or as the clock the board shows
     * ({@code 8:20}), so a value can be copied straight off the page.</p>
     *
     * <p>ADMIN only — an invented score is public the moment it is written.</p>
     */
    @Transactional
    @PreAuthorize(AdminRole.IS_ADMIN)
    public ScoreResult setScore(String actor, UUID playerId, String mode, String time,
                                String kills, String level, String bosses, String callerIp) {
        AdminPlayerDetail player = players.findDetail(playerId)
                .orElseThrow(() -> new NotFoundException("no such player"));

        String chosen = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!LeaderboardService.MODES.contains(chosen)) {
            return ScoreResult.refused("Mode must be one of " + modes() + ".");
        }

        Integer seconds = parseClock(time);
        if (seconds == null || seconds > MAX_SECONDS) {
            return ScoreResult.refused("Time must be seconds (500) or minutes:seconds (8:20), "
                    + "at most a day.");
        }
        Integer killCount = parseWhole(kills, Integer.MAX_VALUE);
        if (killCount == null) {
            return ScoreResult.refused("Kills must be a whole number, 0 or more.");
        }
        Integer reached = parseWhole(level, MAX_LEVEL);
        if (reached == null || reached < 1) {
            return ScoreResult.refused("Level must be a whole number from 1 to " + MAX_LEVEL + ".");
        }
        Integer bossCount = parseWhole(bosses, MAX_BOSSES);
        if (bossCount == null) {
            return ScoreResult.refused("Bosses must be a whole number from 0 to " + MAX_BOSSES + ".");
        }
        if (!LeaderboardService.isPlausible(seconds, killCount)) {
            return ScoreResult.refused("No run can make " + killCount + " kills in " + seconds
                    + " seconds — the game itself would refuse it.");
        }

        String before = queries.listByPlayer(playerId).stream()
                .filter(row -> row.mode().equals(chosen))
                .findFirst()
                .map(AdminBoardService::describe)
                .orElse(null);

        queries.setEntry(playerId, chosen, seconds, killCount, reached, bossCount);

        String after = describe(seconds, killCount, reached, bossCount);
        audit.scoreSet(actor, playerId, player.displayName(), chosen, before, after, callerIp);
        log.info("admin '{}' set the {} score of player {} to {}", actor, chosen, playerId, after);
        return new ScoreResult(true, null);
    }

    private static String describe(AdminBoardRow row) {
        return describe(Math.round(row.survivedSeconds()), row.kills(), row.reachedLevel(),
                row.bossesDefeated());
    }

    private static String describe(long seconds, int kills, int level, int bosses) {
        return String.format(Locale.ROOT, "%d:%02d, %d kills, level %d, %d bosses",
                seconds / 60, seconds % 60, kills, level, bosses);
    }

    /** {@code 500} or {@code 8:20}. Null for anything else, including a negative. */
    private static Integer parseClock(String raw) {
        String text = raw == null ? "" : raw.trim();
        int colon = text.indexOf(':');
        if (colon < 0) {
            return parseWhole(text, Integer.MAX_VALUE);
        }
        Integer minutes = parseWhole(text.substring(0, colon), Integer.MAX_VALUE / 60);
        Integer secs = parseWhole(text.substring(colon + 1), 59);
        return minutes == null || secs == null ? null : minutes * 60 + secs;
    }

    private static Integer parseWhole(String raw, int max) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || text.length() > 10 || !text.chars().allMatch(Character::isDigit)) {
            return null;
        }
        long value = Long.parseLong(text);
        return value <= max ? (int) value : null;
    }

    /**
     * Remove one player's score in one mode.
     *
     * <p>Returns whether a row was actually removed, and <b>that return value — not the
     * request — is what decides whether anything is audited.</b> Two identical-looking requests
     * can differ here: if somebody else removed the score first, or the page was stale, this
     * touched nothing, and recording "score removed" would be a claim the administrator has no
     * way to check.</p>
     *
     * <p>{@code @Transactional} so the removal and its audit entry commit together.</p>
     *
     * @return true if a score was removed, false if there was nothing there
     */
    @Transactional
    public boolean removeScore(String actor, UUID playerId, String mode, String callerIp) {
        String selected = normalise(mode);

        if (queries.deleteEntry(playerId, selected) == 0) {
            return false;
        }

        audit.scoreRemoved(actor, playerId, selected, callerIp);
        log.info("admin '{}' removed the {} score of player {}", actor, selected, playerId);
        return true;
    }

    @Transactional(readOnly = true)
    public long testScoreCount() {
        return queries.countTestEntries();
    }

    /**
     * Remove every test player's score, in every mode. The test players themselves stay.
     *
     * <p>ADMIN only, like {@link #setScore}: the two are one feature, filling a board by hand and
     * emptying it again. Audited once with the count, and only when something was removed.</p>
     *
     * @return the number of scores removed
     */
    @Transactional
    @PreAuthorize(AdminRole.IS_ADMIN)
    public int removeTestScores(String actor, String callerIp) {
        int removed = queries.deleteTestEntries();
        if (removed > 0) {
            audit.testScoresRemoved(actor, removed, callerIp);
            log.info("admin '{}' removed all {} test-player scores", actor, removed);
        }
        return removed;
    }
}
