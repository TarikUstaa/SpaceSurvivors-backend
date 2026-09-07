package com.tarikusta.spacesurvivors.leaderboard;

import com.tarikusta.spacesurvivors.auth.Caller;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP layer for the leaderboard. As with the progress endpoints, the {@link Caller} is
 * put on the request by the auth filter and read here with {@code @RequestAttribute}.
 *
 * <p>{@code @Valid} is what makes Spring run the constraints declared on
 * {@link LeaderboardDtos.Submission} before this method body executes.
 */
@RestController
@RequestMapping("/v1/leaderboard")
public class LeaderboardController {

    private final LeaderboardService service;

    public LeaderboardController(LeaderboardService service) {
        this.service = service;
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
    public LeaderboardDtos.SubmitResult submit(@RequestAttribute(Caller.ATTR) Caller caller,
                                         @Valid @RequestBody LeaderboardDtos.Submission run) {
        return service.submit(caller, run);
    }

    /**
     * GET /v1/leaderboard?mode=infinite&amp;limit=100 — the public board plus the caller's
     * own standing. {@code limit} is optional and clamped server-side.
     */
    @GetMapping
    public LeaderboardDtos.Board board(@RequestAttribute(Caller.ATTR) Caller caller,
                                 @RequestParam String mode,
                                 @RequestParam(defaultValue = "100") int limit) {
        return service.board(caller, mode, limit);
    }
}
