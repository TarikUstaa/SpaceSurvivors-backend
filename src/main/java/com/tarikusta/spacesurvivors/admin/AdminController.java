package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.auth.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
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
    private final AdminProgressService saves;

    public AdminController(AdminPlayerService players, AdminBoardService board,
                           AdminProgressService saves) {
        this.players = players;
        this.board = board;
        this.saves = saves;
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
        return "redirect:/admin/overview";
    }

    /**
     * Where a signed-in person lands when their role does not cover what they asked for.
     *
     * <p>A real page with a real 403, reached by a redirect from both refusal paths —
     * {@code AdminSecurityConfig.StaleFormHandler} for the URL rules, {@code AdminErrorHandler}
     * for {@code @PreAuthorize}. {@code @ResponseStatus} sets the status on an ordinary render,
     * which is the difference from {@code sendError}: no error dispatch, so the API chain never
     * gets a say in what this looks like.</p>
     */
    @GetMapping("/forbidden")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String forbidden(Model model, Authentication authentication) {
        model.addAttribute("title", "Not allowed");
        model.addAttribute("detail",
                "Your account's role does not include that page or action. "
                + "An administrator can change the role from the Users page.");
        model.addAttribute("admin", authentication.getName());
        return "admin/error";
    }

    /**
     * The player list: searched, filtered and paged. The three parameters stay in the URL, so a
     * search is a link that can be bookmarked or pasted to somebody.
     */
    @GetMapping("/players")
    public String players(@RequestParam(required = false) String q,
                          @RequestParam(required = false) String filter,
                          @RequestParam(defaultValue = "0") int page,
                          Model model, Authentication authentication) {
        AdminPlayerService.Overview overview = players.overview();
        AdminPlayerSearch.Page results = players.search(q, filter, page);

        model.addAttribute("results", results);
        model.addAttribute("players", results.rows());
        model.addAttribute("q", q == null ? "" : q.trim());
        model.addAttribute("filter", AdminPlayerSearch.Filter.parse(filter));
        model.addAttribute("filters", AdminPlayerSearch.Filter.values());
        model.addAttribute("playerCount", overview.total());
        model.addAttribute("savedCount", overview.withSaves());
        model.addAttribute("testCount", overview.testPlayers());
        model.addAttribute("deletePhrase", AdminPlayerService.DELETE_TEST_PLAYERS_PHRASE);
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
        model.addAttribute("modes", board.modes());
        model.addAttribute("admin", authentication.getName());
        return "admin/player";
    }

    /**
     * Create a test player and go straight to their page, where their scores are set.
     *
     * <p>{@code /players/new} is a POST only. A GET to it is read as a player id that is not a
     * UUID and answers the ordinary not-found page, which is the right answer for a URL that does
     * not name a page.</p>
     */
    @PostMapping("/players/new")
    public String createTestPlayer(@RequestParam(required = false) String displayName,
                                   RedirectAttributes redirect,
                                   Authentication authentication,
                                   HttpServletRequest request) {
        AdminPlayerService.Created result = players.createTestPlayer(
                authentication.getName(), displayName, ClientAddress.of(request));

        return switch (result.outcome()) {
            case CREATED -> {
                redirect.addFlashAttribute("message", "Created test player "
                        + result.displayName() + ". Nobody can sign in as it; set its scores below.");
                yield "redirect:/admin/players/" + result.playerId();
            }
            case INVALID_NAME -> {
                redirect.addFlashAttribute("warning", "Nothing was created — " + result.problem() + ".");
                yield "redirect:/admin/players";
            }
            case NAME_TAKEN -> {
                redirect.addFlashAttribute("warning", result.displayName().isEmpty()
                        ? "Nothing was created — no free generated name was found. Try again."
                        : "Nothing was created — '" + result.displayName() + "' is already taken.");
                yield "redirect:/admin/players";
            }
        };
    }

    /**
     * Remove every test player. Not under {@code /players/}, where "test" would read as a player id
     * to the {@code /players/{playerId}/delete} mapping and its URL rule.
     */
    @PostMapping("/test-players/delete")
    public String deleteTestPlayers(@RequestParam(required = false) String confirmation,
                                    RedirectAttributes redirect,
                                    Authentication authentication,
                                    HttpServletRequest request) {
        AdminPlayerService.TestCleanup result = players.deleteTestPlayers(
                authentication.getName(), confirmation, ClientAddress.of(request));

        if (!result.confirmed()) {
            redirect.addFlashAttribute("warning", "Nothing was deleted — type the phrase exactly.");
        } else if (result.deleted() == 0) {
            redirect.addFlashAttribute("warning", "There were no test players to delete.");
        } else {
            redirect.addFlashAttribute("message", "Deleted " + result.deleted()
                    + " test players, their saves and their scores.");
        }
        return "redirect:/admin/players";
    }

    @PostMapping("/players/{playerId}/rename")
    public String rename(@PathVariable UUID playerId,
                         @RequestParam(required = false) String displayName,
                         RedirectAttributes redirect,
                         Authentication authentication,
                         HttpServletRequest request) {
        AdminPlayerService.Renamed result = players.rename(
                authentication.getName(), playerId, displayName, ClientAddress.of(request));

        switch (result.outcome()) {
            case RENAMED -> redirect.addFlashAttribute("message",
                    "Renamed '" + result.from() + "' to '" + result.to() + "'.");
            case UNCHANGED -> redirect.addFlashAttribute("warning", "That already is the name.");
            case INVALID -> redirect.addFlashAttribute("warning",
                    "Not renamed — " + result.problem() + ".");
            case TAKEN -> redirect.addFlashAttribute("warning",
                    "Not renamed — '" + result.to() + "' is already taken.");
        }
        return "redirect:/admin/players/" + playerId;
    }

    /** Set one score by hand. Every outcome returns to the player, where the scores are listed. */
    @PostMapping("/players/{playerId}/score")
    public String setScore(@PathVariable UUID playerId,
                           @RequestParam(required = false) String mode,
                           @RequestParam(required = false) String time,
                           @RequestParam(required = false) String kills,
                           @RequestParam(required = false) String level,
                           @RequestParam(required = false) String bosses,
                           RedirectAttributes redirect,
                           Authentication authentication,
                           HttpServletRequest request) {
        AdminBoardService.ScoreResult result = board.setScore(authentication.getName(), playerId,
                mode, time, kills, level, bosses, ClientAddress.of(request));

        if (result.written()) {
            redirect.addFlashAttribute("message", "Score set. It is on the public board now.");
        } else {
            redirect.addFlashAttribute("warning", "Nothing was written. " + result.problem());
        }
        return "redirect:/admin/players/" + playerId;
    }

    /**
     * The save editor. A player who has never saved has nothing to edit, and is sent back to their
     * page with a sentence rather than shown an empty form that would create a save out of nothing.
     */
    @GetMapping("/players/{playerId}/edit")
    public String editSave(@PathVariable UUID playerId, Model model,
                           RedirectAttributes redirect, Authentication authentication) {
        return saves.form(playerId)
                .map(form -> {
                    model.addAttribute("form", form);
                    model.addAttribute("admin", authentication.getName());
                    return "admin/player-edit";
                })
                .orElseGet(() -> {
                    redirect.addFlashAttribute("warning",
                            "This player has no cloud save yet, so there is nothing to edit.");
                    return "redirect:/admin/players/" + playerId;
                });
    }

    /**
     * Apply an edit. The whole form arrives as a map because the service owns the list of fields
     * and their rules; binding it to a Java type here would be a second list to keep in step.
     *
     * <p>A refused edit returns to the form — freshly drawn from the database, so a STALE refusal
     * shows the save as it is now — and a saved one to the player's page.</p>
     */
    @PostMapping("/players/{playerId}/edit")
    public String saveEdit(@PathVariable UUID playerId,
                           @RequestParam Map<String, String> form,
                           RedirectAttributes redirect,
                           Authentication authentication,
                           HttpServletRequest request) {

        AdminProgressService.Result result = saves.edit(
                authentication.getName(), playerId, form, ClientAddress.of(request));

        return switch (result.outcome()) {
            case SAVED -> {
                redirect.addFlashAttribute("message", "Save updated: "
                        + String.join(", ", result.changes())
                        + ". The game takes this copy over its own on its next sync.");
                yield "redirect:/admin/players/" + playerId;
            }
            case UNCHANGED -> {
                redirect.addFlashAttribute("warning", "Nothing changed, so nothing was written.");
                yield "redirect:/admin/players/" + playerId;
            }
            case NO_SAVE -> {
                redirect.addFlashAttribute("warning",
                        "This player has no cloud save yet, so there is nothing to edit.");
                yield "redirect:/admin/players/" + playerId;
            }
            case STALE -> {
                redirect.addFlashAttribute("warning", "The game saved this player while the form "
                        + "was open. Nothing was written — the form below shows the save as it is "
                        + "now; make the change again.");
                yield "redirect:/admin/players/" + playerId + "/edit";
            }
            case INVALID -> {
                redirect.addFlashAttribute("warning", "Nothing was written. " + result.problem());
                yield "redirect:/admin/players/" + playerId + "/edit";
            }
        };
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
