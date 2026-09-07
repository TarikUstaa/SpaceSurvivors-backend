package com.tarikusta.spacesurvivors.score;

import com.tarikusta.spacesurvivors.auth.Caller;
import com.tarikusta.spacesurvivors.player.PlayerService;
import com.tarikusta.spacesurvivors.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The rules of the leaderboard. No SQL, no HTTP.
 *
 * <p>Two kinds of check happen before a score is accepted:
 * <ol>
 *   <li>field shape — handled declaratively by the annotations on
 *       {@link ScoreDtos.Submission}, so a negative kill count never reaches here;</li>
 *   <li>business plausibility — below. A run can be perfectly well-formed and still
 *       be something the game cannot produce, e.g. 50 000 kills in 10 seconds.</li>
 * </ol>
 * This is a soft anti-cheat: it stops obvious garbage, not a determined attacker.
 * Real protection needs a server-authoritative run, which is out of scope.
 */
@Service
public class ScoreService {

    /** The game's two modes. Anything else is a client bug or someone poking the API. */
    private static final Set<String> MODES = Set.of("infinite", "campaign");

    /** Even a perfect build cannot sustain this; well above anything the balance sim produces. */
    private static final double MAX_KILLS_PER_SECOND = 60;

    /** Below this the kill-rate ratio is too noisy to judge (spawn burst at run start). */
    private static final double MIN_SECONDS_TO_JUDGE_RATE = 5;

    /** Guard rails for GET: never let a caller ask for the whole table. */
    private static final int MAX_BOARD_SIZE = 200;

    private final ScoreRepository scores;
    private final PlayerService players;

    public ScoreService(ScoreRepository scores, PlayerService players) {
        this.scores = scores;
        this.players = players;
    }

    /**
     * Record a finished run. The entry is only written when it beats the player's
     * stored best, so the table stays one row per player per mode.
     *
     * <p>{@code @Transactional}: resolving the player, reading the old best and the
     * write all commit together or not at all.
     */
    @Transactional
    public ScoreDtos.SubmitResult submit(Caller caller, ScoreDtos.Submission run) {
        String mode = normaliseMode(run.mode());
        rejectImplausible(run);

        UUID playerId = players.resolveOrCreate(caller);

        double previousBest = scores.personalBest(playerId, mode).orElse(-1.0);
        boolean isNewRecord = run.survivedSeconds() > previousBest;
        if (isNewRecord) {
            scores.saveBest(playerId, mode, run.survivedSeconds(), run.kills(),
                    run.reachedLevel(), run.bossesDefeated());
        }

        // read back rather than compute: this is what the board will actually show
        return scores.findMe(playerId, mode)
                .map(me -> new ScoreDtos.SubmitResult(me.survivedSeconds(), isNewRecord, me.rank()))
                .orElseGet(() -> new ScoreDtos.SubmitResult(run.survivedSeconds(), isNewRecord, null));
    }

    /** Top {@code limit} entries of a mode, plus where the caller sits. */
    @Transactional
    public ScoreDtos.Board board(Caller caller, String mode, int limit) {
        String normalised = normaliseMode(mode);
        int capped = Math.clamp(limit, 1, MAX_BOARD_SIZE);

        List<ScoreDtos.BoardEntry> entries = new ArrayList<>();
        int rank = 1;
        for (ScoreRepository.BoardRow row : scores.top(normalised, capped)) {
            entries.add(new ScoreDtos.BoardEntry(rank++, row.displayName(),
                    row.survivedSeconds(), row.kills(), row.reachedLevel()));
        }

        ScoreDtos.Me me = scores.findMe(players.resolveOrCreate(caller), normalised).orElse(null);
        return new ScoreDtos.Board(normalised, entries, me);
    }

    /** Accepts "Infinite" / " infinite " and rejects anything that is not a real mode. */
    private String normaliseMode(String mode) {
        String normalised = Optional.ofNullable(mode)
                .map(m -> m.trim().toLowerCase(Locale.ROOT))
                .orElse("");
        if (!MODES.contains(normalised)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "mode must be one of " + MODES);
        }
        return normalised;
    }

    private void rejectImplausible(ScoreDtos.Submission run) {
        if (run.survivedSeconds() >= MIN_SECONDS_TO_JUDGE_RATE
                && run.kills() / run.survivedSeconds() > MAX_KILLS_PER_SECOND) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "kill rate is not achievable in that time");
        }
    }
}
