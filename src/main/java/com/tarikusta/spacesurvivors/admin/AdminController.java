package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * The backoffice pages.
 *
 * <p>A plain {@code @Controller}, not a {@code @RestController}: these methods return the
 * <em>name of a template</em> and Thymeleaf renders it on the server. The browser receives
 * finished HTML and runs no JavaScript of ours — there is no second application here, no
 * build step, and no API for the pages to call, because they read the database directly
 * through the same repositories the game's endpoints use.</p>
 *
 * <p>Nothing in this class checks who is asking. {@link AdminSecurityConfig} refuses every
 * request to {@code /admin/**} that is not an authenticated administrator before a method
 * here is reached, which is the only place that check should exist: a controller that
 * remembers to verify is a controller that can forget.</p>
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminPlayerQueries players;
    private final AdminLeaderboardQueries board;

    public AdminController(AdminPlayerQueries players, AdminLeaderboardQueries board) {
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
        List<AdminPlayerRow> rows = players.listAll();

        model.addAttribute("players", rows);
        model.addAttribute("playerCount", rows.size());
        model.addAttribute("savedCount", rows.stream().filter(AdminPlayerRow::hasSave).count());
        model.addAttribute("admin", authentication.getName());
        return "admin/players";
    }

    /**
     * One player: their profile, their save, and the scores they hold.
     *
     * <p>A 404 for an id that is not there, rather than an empty page pretending the player
     * exists. {@code NotFoundException} is the application's own — the same one the game's
     * endpoints throw — so this needs no opinion about status codes.</p>
     */
    @GetMapping("/players/{playerId}")
    public String player(@PathVariable UUID playerId, Model model, Authentication authentication) {
        AdminPlayerDetail detail = players.findDetail(playerId)
                .orElseThrow(() -> new NotFoundException("no such player"));

        model.addAttribute("player", detail);
        model.addAttribute("scores", board.listByPlayer(playerId));
        model.addAttribute("admin", authentication.getName());
        return "admin/player";
    }

    /**
     * Delete a player and everything of theirs.
     *
     * <p><b>The confirmation is checked here, not in the browser.</b> The form asks for the
     * player's name to be typed out, and this compares it before deleting anything. That
     * ordering is the entire point: a dialog is a suggestion to whoever is at the keyboard,
     * while this is a rule about the request. A request that arrives without the right name —
     * from a stale tab, a double submit, a script, or a page on another site — does not
     * delete a player, and no amount of clicking elsewhere changes that.</p>
     *
     * <p>The typing is not security theatre either. It is the one irreversible action in the
     * backoffice, and it is aimed at a row a mis-click could just as easily have chosen; the
     * name is how the person says <em>which</em> row they meant, not merely that they meant
     * one.</p>
     */
    @PostMapping("/players/{playerId}/delete")
    @Transactional
    public String deletePlayer(@PathVariable UUID playerId,
                               @RequestParam(required = false) String confirmName,
                               RedirectAttributes redirect,
                               Authentication authentication) {
        AdminPlayerDetail detail = players.findDetail(playerId)
                .orElseThrow(() -> new NotFoundException("no such player"));

        if (!detail.displayName().equals(confirmName == null ? "" : confirmName.trim())) {
            redirect.addFlashAttribute("warning",
                    "Nothing was deleted — the name did not match.");
            return "redirect:/admin/players/" + playerId;
        }

        players.deletePlayer(playerId);

        // The save and the scores went with them, by the cascade on those foreign keys. Said
        // out loud in the log because "deleted a player" undersells what just happened.
        log.warn("admin '{}' deleted player {} ('{}') along with their save and scores",
                authentication.getName(), playerId, detail.displayName());

        redirect.addFlashAttribute("message",
                "Deleted " + detail.displayName() + ", their save and their scores.");
        return "redirect:/admin/players";
    }
}
