package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.leaderboard.LeaderboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * The leaderboard, and the one thing the backoffice may change about it.
 *
 * <p>This is the first page here that writes. Everything up to now only read, and the
 * difference is not the SQL — it is that a wrong click now costs somebody their record. Three
 * things follow from that, and all three are visible below: the removal is a POST and not a
 * link, it names exactly one row, and it says in the log who did it.</p>
 */
@Controller
@RequestMapping("/admin/leaderboard")
public class AdminLeaderboardController {

    private static final Logger log = LoggerFactory.getLogger(AdminLeaderboardController.class);

    private static final String DEFAULT_MODE = "infinite";

    private final AdminLeaderboardQueries board;

    public AdminLeaderboardController(AdminLeaderboardQueries board) {
        this.board = board;
    }

    @GetMapping
    public String board(@RequestParam(required = false) String mode,
                        Model model,
                        Authentication authentication) {
        String selected = normalise(mode);

        model.addAttribute("mode", selected);
        model.addAttribute("modes", LeaderboardService.MODES.stream().sorted().toList());
        model.addAttribute("rows", board.listByMode(selected));
        model.addAttribute("admin", authentication.getName());
        return "admin/leaderboard";
    }

    /**
     * Remove one score.
     *
     * <p>A POST, because it changes something — and because a GET would be a link, which is
     * a thing a browser may follow on its own and another site may embed. With the session
     * cookie attached automatically, a {@code <img src="/admin/leaderboard/delete?...">} on
     * any page an administrator visits would be a working removal. The form carries the CSRF
     * token that makes this request distinguishable from that one.</p>
     *
     * <p>Redirects rather than rendering: after a POST, a rendered page is one refresh away
     * from asking the browser to submit it again. The message survives the redirect as a
     * flash attribute.</p>
     */
    @PostMapping("/delete")
    @Transactional
    public String delete(@RequestParam UUID playerId,
                         @RequestParam String mode,
                         RedirectAttributes redirect,
                         Authentication authentication) {
        String selected = normalise(mode);
        int removed = board.deleteEntry(playerId, selected);

        if (removed == 0) {
            // Not an error worth a stack trace, but not a success either: somebody else
            // removed it, or the page was stale. Saying "removed" here would be a lie the
            // administrator has no way to notice.
            redirect.addFlashAttribute("warning", "That score was already gone.");
        } else {
            // The audit trail, such as it is. A real one belongs in a table; this at least
            // means a removal is never something nobody can account for.
            log.info("admin '{}' removed the {} score of player {}",
                    authentication.getName(), selected, playerId);
            redirect.addFlashAttribute("message", "Score removed.");
        }

        return "redirect:/admin/leaderboard?mode=" + selected;
    }

    /**
     * Fall back to a real mode rather than failing.
     *
     * <p>The parameter reaches this method from a query string, so it can be anything at all.
     * The list it is checked against is {@link LeaderboardService#MODES} — the same set the
     * game's own endpoint validates against, so the two cannot drift into disagreeing about
     * what a mode is.</p>
     */
    private String normalise(String mode) {
        String candidate = mode == null ? "" : mode.trim().toLowerCase();
        return LeaderboardService.MODES.contains(candidate) ? candidate : DEFAULT_MODE;
    }
}
