package com.tarikusta.spacesurvivors.admin;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

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

    private final AdminPlayerQueries players;

    public AdminController(AdminPlayerQueries players) {
        this.players = players;
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
}
