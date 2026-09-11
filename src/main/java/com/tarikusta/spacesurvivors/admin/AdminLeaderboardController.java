package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.auth.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * The leaderboard page, and the one thing the backoffice may change about it.
 *
 * <p>The rules are in {@link AdminBoardService}: which modes exist, what an unrecognised one
 * falls back to, and what it means to remove a score. What is decided here is HTTP and only
 * HTTP — that the removal is a POST, and where the browser goes afterwards.</p>
 *
 * <p><b>A POST and not a link</b>, because a GET that changes something is a URL a browser may
 * follow on its own and another site may embed. With the session cookie attached
 * automatically, an {@code <img src="/admin/leaderboard/delete?...">} on any page an
 * administrator visits would be a working removal. The form carries the CSRF token that makes
 * this request distinguishable from that one.</p>
 *
 * <p><b>A redirect and not a rendered page</b>, because after a POST a rendered page is one
 * refresh away from asking the browser to submit it again. The message survives the redirect
 * as a flash attribute.</p>
 */
@Controller
@RequestMapping("/admin/leaderboard")
public class AdminLeaderboardController {

    private final AdminBoardService board;

    public AdminLeaderboardController(AdminBoardService board) {
        this.board = board;
    }

    @GetMapping
    public String board(@RequestParam(required = false) String mode,
                        Model model,
                        Authentication authentication) {
        String selected = board.normalise(mode);

        model.addAttribute("mode", selected);
        model.addAttribute("modes", board.modes());
        model.addAttribute("rows", board.rows(selected));
        model.addAttribute("admin", authentication.getName());
        return "admin/leaderboard";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam UUID playerId,
                         @RequestParam String mode,
                         RedirectAttributes redirect,
                         Authentication authentication,
                         HttpServletRequest request) {

        String selected = board.normalise(mode);
        boolean removed = board.removeScore(
                authentication.getName(), playerId, selected, ClientAddress.of(request));

        if (removed) {
            redirect.addFlashAttribute("message", "Score removed.");
        } else {
            // Not an error worth a stack trace, but not a success either: somebody else
            // removed it, or the page was stale. Saying "removed" here would be a lie the
            // administrator has no way to notice.
            redirect.addFlashAttribute("warning", "That score was already gone.");
        }

        return "redirect:/admin/leaderboard?mode=" + selected;
    }
}
