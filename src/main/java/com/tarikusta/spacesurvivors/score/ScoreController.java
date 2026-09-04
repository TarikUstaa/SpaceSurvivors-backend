package com.tarikusta.spacesurvivors.score;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP layer for the leaderboard. As with the profile endpoints, {@code userId} is
 * put on the request by the auth filter and read here with {@code @RequestAttribute}.
 *
 * <p>{@code @Valid} is what makes Spring run the constraints declared on
 * {@link ScoreDtos.Submission} before this method body executes.
 */
@RestController
@RequestMapping("/v1/scores")
public class ScoreController {

    private final ScoreService service;

    public ScoreController(ScoreService service) {
        this.service = service;
    }

    /**
     * POST /v1/scores — report a finished run.
     * <ul>
     *   <li>200 {personalBest, isNewRecord, rank}</li>
     *   <li>400 malformed body (a field failed validation)</li>
     *   <li>422 well-formed but not a run the game could produce</li>
     * </ul>
     */
    @PostMapping
    public ScoreDtos.SubmitResult submit(@RequestAttribute("userId") String userId,
                                         @Valid @RequestBody ScoreDtos.Submission run) {
        return service.submit(userId, run);
    }

    /**
     * GET /v1/scores?mode=infinite&amp;limit=100 — the public board plus the caller's
     * own standing. {@code limit} is optional and clamped server-side.
     */
    @GetMapping
    public ScoreDtos.Board board(@RequestAttribute("userId") String userId,
                                 @RequestParam String mode,
                                 @RequestParam(defaultValue = "100") int limit) {
        return service.board(userId, mode, limit);
    }
}
