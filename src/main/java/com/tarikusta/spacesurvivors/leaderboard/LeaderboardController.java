package com.tarikusta.spacesurvivors.leaderboard;

import com.tarikusta.spacesurvivors.auth.CurrentPlayer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * HTTP for the leaderboard. Every method delegates; no rules live here.
 *
 * <p>{@code @CurrentPlayer} is filled from the verified token's subject claim, so the caller's
 * identity is never something the request body can claim — see
 * {@link com.tarikusta.spacesurvivors.auth.CurrentPlayerArgumentResolver}.</p>
 *
 * <p>{@code @Valid} is what makes Spring run the constraints declared on
 * {@link LeaderboardDtos.Submission} before a method body here executes.</p>
 */
@RestController
@RequestMapping("/v1/leaderboard")
public class LeaderboardController {

    private final LeaderboardService leaderboard;

    public LeaderboardController(LeaderboardService leaderboard) {
        this.leaderboard = leaderboard;
    }

    /**
     * POST /v1/leaderboard — report a finished run.
     * <ul>
     *   <li>200 {personalBest, isNewRecord, rank}</li>
     *   <li>400 malformed body (a field failed validation)</li>
     *   <li>422 well-formed but not a run the game could produce</li>
     * </ul>
     */
    @PostMapping
    public LeaderboardDtos.SubmitResult submit(@CurrentPlayer UUID playerId,
                                               @Valid @RequestBody LeaderboardDtos.Submission run) {
        return leaderboard.submit(playerId, run);
    }

    /**
     * GET /v1/leaderboard?mode=infinite&amp;limit=100 — the public board plus the caller's own
     * standing. {@code limit} is optional and clamped server-side.
     */
    @GetMapping
    public LeaderboardDtos.Board board(@CurrentPlayer UUID playerId,
                                       @RequestParam String mode,
                                       @RequestParam(defaultValue = "100") int limit) {
        return leaderboard.board(playerId, mode, limit);
    }
}
