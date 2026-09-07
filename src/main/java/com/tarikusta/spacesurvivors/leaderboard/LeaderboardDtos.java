package com.tarikusta.spacesurvivors.leaderboard;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * The request/response shapes of the leaderboard API, kept together because they
 * only describe one endpoint pair. Records are immutable data carriers — Jackson
 * fills them from JSON on the way in and serialises them on the way out.
 */
public final class LeaderboardDtos {

    private LeaderboardDtos() {
    }

    /**
     * Body of {@code POST /v1/leaderboard}.
     *
     * <p>The annotations are <em>bean validation</em>: Spring checks them before the
     * controller method runs (because of {@code @Valid}) and rejects the request with
     * 400 if any fails. They cover single-field shape only — "is this number even
     * possible?". Cross-field plausibility ("could a run really get this many kills
     * in this much time?") is a business rule and lives in {@link LeaderboardService}.
     */
    public record Submission(
            @NotBlank
            String mode,

            /* 6 hours — far past any real run, but a cheap ceiling against garbage */
            @PositiveOrZero @DecimalMax("21600")
            double survivedSeconds,

            @PositiveOrZero
            int kills,

            @Min(1)
            int reachedLevel,

            @PositiveOrZero
            int bossesDefeated
    ) {
    }

    /**
     * Response of {@code POST /v1/leaderboard}.
     *
     * @param personalBest the player's best survival time in this mode after this submission
     * @param isNewRecord  whether this run beat the stored best (and was therefore written)
     * @param rank         1-based position on the board, or null if the player has no entry
     */
    public record SubmitResult(double personalBest, boolean isNewRecord, Integer rank) {
    }

    /** One row of the public board. */
    public record BoardEntry(int rank, String displayName, double survivedSeconds,
                             int kills, int reachedLevel) {
    }

    /**
     * Response of {@code GET /v1/leaderboard}.
     *
     * @param me the caller's own standing, or null when they have no entry in this mode
     */
    public record Board(String mode, List<BoardEntry> entries, Me me) {
    }

    public record Me(int rank, double survivedSeconds) {
    }
}
