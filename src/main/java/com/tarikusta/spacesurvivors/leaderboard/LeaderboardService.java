package com.tarikusta.spacesurvivors.leaderboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tarikusta.spacesurvivors.domain.RuleViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;

/**
 * The rules of the leaderboard. No SQL, no HTTP.
 *
 * <p>Two kinds of check happen before a score is accepted:
 * <ol>
 *   <li>field shape — handled declaratively by the annotations on
 *       {@link LeaderboardDtos.Submission}, so a negative kill count never reaches here;</li>
 *   <li>business plausibility — below. A run can be perfectly well-formed and still
 *       be something the game cannot produce, e.g. 50 000 kills in 10 seconds.</li>
 * </ol>
 * This is a soft anti-cheat: it stops obvious garbage, not a determined attacker.
 * Real protection needs a server-authoritative run, which is out of scope.
 */
@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    /** The game's two modes. Anything else is a client bug or someone poking the API. */
    private static final Set<String> MODES = Set.of("infinite", "campaign");

    /** Even a perfect build cannot sustain this; well above anything the balance sim produces. */
    private static final double MAX_KILLS_PER_SECOND = 60;

    /** Below this the kill-rate ratio is too noisy to judge (spawn burst at run start). */
    private static final double MIN_SECONDS_TO_JUDGE_RATE = 5;

    /** Guard rails for GET: never let a caller ask for the whole table. */
    private static final int MAX_BOARD_SIZE = 200;

    private final LeaderboardEntryRepository board;

    /**
     * No PlayerService: the token already carried the player id, so nothing here has to
     * ask who is calling. That dependency existed only to turn a device into a player on
     * every request.
     */
    public LeaderboardService(LeaderboardEntryRepository board) {
        this.board = board;
    }

    /**
     * Record a finished run. The entry is only written when it beats the player's
     * stored best, so the table stays one row per player per mode.
     *
     * <p>{@code @Transactional}: resolving the player, reading the old best and the
     * write all commit together or not at all.
     */
    @Transactional
    public LeaderboardDtos.SubmitResult submit(UUID playerId, LeaderboardDtos.Submission run) {
        String mode = normaliseMode(run.mode());
        rejectImplausible(run);


        // Narrow to float first: survived_seconds is a real column, so this is the value
        // that will actually be stored. Comparing the wider incoming double against a
        // narrowed round-trip would report a new record every time the same run was
        // resubmitted.
        float submitted = (float) run.survivedSeconds();
        float previousBest = board.findPersonalBest(playerId, mode).orElse(-1.0).floatValue();
        boolean isNewRecord = submitted > previousBest;
        if (isNewRecord) {
            board.saveBest(playerId, mode, run.survivedSeconds(), run.kills(),
                    run.reachedLevel(), run.bossesDefeated());
        }

        // read back rather than compute: this is what the board will actually show
        return board.findStanding(playerId, mode)
                .map(standing -> new LeaderboardDtos.SubmitResult(
                        standing.getSeconds(), isNewRecord, standing.getRank()))
                .orElseGet(() -> new LeaderboardDtos.SubmitResult(run.survivedSeconds(), isNewRecord, null));
    }

    /**
     * Top {@code limit} entries of a mode, plus where the caller sits.
     *
     * <p>Read-only, and free of any device lookup: the token already carried the player
     * id, so the most-read endpoint in the API touches nothing but the board itself.</p>
     */
    @Transactional(readOnly = true)
    public LeaderboardDtos.Board board(UUID playerId, String mode, int limit) {
        String normalised = normaliseMode(mode);
        int capped = Math.clamp(limit, 1, MAX_BOARD_SIZE);

        List<LeaderboardDtos.BoardEntry> entries = new ArrayList<>();
        int rank = 1;
        for (BoardRow row : board.topEntries(normalised, PageRequest.ofSize(capped))) {
            entries.add(new LeaderboardDtos.BoardEntry(rank++, row.displayName(),
                    row.survivedSeconds(), row.kills(), row.reachedLevel()));
        }

        LeaderboardDtos.Me me = board.findStanding(playerId, normalised)
                .map(standing -> new LeaderboardDtos.Me(standing.getRank(), standing.getSeconds()))
                .orElse(null);
        return new LeaderboardDtos.Board(normalised, entries, me);
    }

    /** Accepts "Infinite" / " infinite " and rejects anything that is not a real mode. */
    private String normaliseMode(String mode) {
        String normalised = Optional.ofNullable(mode)
                .map(m -> m.trim().toLowerCase(Locale.ROOT))
                .orElse("");
        if (!MODES.contains(normalised)) {
            throw new RuleViolationException("mode must be one of " + MODES);
        }
        return normalised;
    }

    private void rejectImplausible(LeaderboardDtos.Submission run) {
        if (run.survivedSeconds() >= MIN_SECONDS_TO_JUDGE_RATE
                && run.kills() / run.survivedSeconds() > MAX_KILLS_PER_SECOND) {
            // WARN, not INFO: a legitimate client cannot produce this, so every one is
            // either a tampered client or a balance change nobody told the server about.
            log.warn("implausible run rejected: {} kills in {}s, mode {}",
                    run.kills(), run.survivedSeconds(), run.mode());
            throw new RuleViolationException("kill rate is not achievable in that time");
        }
    }
}
