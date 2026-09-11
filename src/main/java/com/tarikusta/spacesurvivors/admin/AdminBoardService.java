package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.leaderboard.LeaderboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    private final AdminLeaderboardQueries queries;
    private final AdminAudit audit;

    public AdminBoardService(AdminLeaderboardQueries queries, AdminAudit audit) {
        this.queries = queries;
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
     */
    public String normalise(String mode) {
        String candidate = mode == null ? "" : mode.trim().toLowerCase();
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
}
