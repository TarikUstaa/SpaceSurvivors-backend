package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.auth.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * The player pages.
 *
 * <p>A plain {@code @Controller}, not a {@code @RestController}: these methods return the
 * <em>name of a template</em> and Thymeleaf renders it on the server. The browser receives
 * finished HTML and runs no JavaScript of ours — there is no second application here, no
 * build step, and no API for the pages to call.</p>
 *
 * <p>Nothing in this class checks who is asking. {@link AdminSecurityConfig} refuses every
 * request to {@code /admin/**} that is not an authenticated administrator before a method
 * here is reached, which is the only place that check should exist: a controller that
 * remembers to verify is a controller that can forget.</p>
 *
 * <p>Nothing in this class decides anything either. Every method reads a request, hands it to
 * {@link AdminPlayerService}, and turns the answer into a page or a redirect — D11. The
 * deletion used to be the exception, with the confirm-by-name rule, the audit write and the
 * transaction all sitting in the method below; they moved, and what is left here is the part
 * that genuinely is about HTTP: which URL to send the browser to, and what to say when it
 * arrives.</p>
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final AdminPlayerService players;
    private final AdminBoardService board;

    public AdminController(AdminPlayerService players, AdminBoardService board) {
        this.players = players;
        this.board = board;
    }

    /**
     * The sign-in page. Spring Security, not this method, handles the POST that follows —
     * hence no {@code @PostMapping}: the form submits to a filter, which is what makes the
     * password check happen before any of our code runs.
     */
    @GetMapping("/login")
    public String login() {
        return "admin/login";
    }

    /** {@code /admin} on its own is a convenience, not a page. */
    @GetMapping
    public String home() {
        return "redirect:/admin/players";
    }

    @GetMapping("/players")
    public String players(Model model, Authentication authentication) {
        AdminPlayerService.Overview overview = players.overview();

        model.addAttribute("players", overview.rows());
        model.addAttribute("playerCount", overview.total());
        model.addAttribute("savedCount", overview.withSaves());
        model.addAttribute("admin", authentication.getName());
        return "admin/players";
    }

    /**
     * One player: their profile, their save, and the scores they hold.
     *
     * <p>A 404 for an id that is not there, rather than an empty page pretending the player
     * exists. The service throws the application's own {@code NotFoundException} — the same one
     * the game's endpoints throw — so this needs no opinion about status codes.</p>
     */
    @GetMapping("/players/{playerId}")
    public String player(@PathVariable UUID playerId, Model model, Authentication authentication) {
        model.addAttribute("player", players.detail(playerId));
        model.addAttribute("scores", board.scoresOf(playerId));
        model.addAttribute("admin", authentication.getName());
        return "admin/player";
    }

    /**
     * Delete a player and everything of theirs.
     *
     * <p>The rule lives in {@link AdminPlayerService#delete}; what this method owns is the
     * answer to "where does the browser go now". A refused delete returns to the player, so
     * the person can see the row still there and read why. A successful one returns to the
     * list, because the page it came from no longer describes anything.</p>
     */
    @PostMapping("/players/{playerId}/delete")
    public String deletePlayer(@PathVariable UUID playerId,
                               @RequestParam(required = false) String confirmName,
                               RedirectAttributes redirect,
                               Authentication authentication,
                               HttpServletRequest request) {

        AdminPlayerService.Deletion result = players.delete(
                authentication.getName(), playerId, confirmName, ClientAddress.of(request));

        return switch (result.outcome()) {
            case NAME_DID_NOT_MATCH -> {
                redirect.addFlashAttribute("warning",
                        "Nothing was deleted — the name did not match.");
                yield "redirect:/admin/players/" + playerId;
            }
            case DELETED -> {
                redirect.addFlashAttribute("message",
                        "Deleted " + result.displayName() + ", their save and their scores.");
                yield "redirect:/admin/players";
            }
        };
    }
}
