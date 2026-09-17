package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.auth.ClientAddress;
import com.tarikusta.spacesurvivors.game.Announcement;
import com.tarikusta.spacesurvivors.game.GameTunable;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/** The "Game" page: what the live game shows and how it is tuned. ADMIN only. */
@Controller
@RequestMapping("/admin/game")
public class AdminGameController {

    private final AdminGameService game;

    public AdminGameController(AdminGameService game) {
        this.game = game;
    }

    @GetMapping
    public String page(Model model, Authentication authentication) {
        model.addAttribute("announcement", game.announcement().orElse(null));
        model.addAttribute("maxLength", Announcement.MAX_LENGTH);
        model.addAttribute("tunables", GameTunable.values());
        model.addAttribute("overrides", game.gameConfigForm());
        model.addAttribute("admin", authentication.getName());
        return "admin/game";
    }

    @PostMapping("/announcement")
    public String setAnnouncement(@RequestParam(required = false) String message,
                                  @RequestParam(required = false) String level,
                                  RedirectAttributes redirect, Authentication authentication,
                                  HttpServletRequest request) {
        report(game.setAnnouncement(authentication.getName(), message, level,
                ClientAddress.of(request)), redirect);
        return "redirect:/admin/game";
    }

    @PostMapping("/announcement/clear")
    public String clearAnnouncement(RedirectAttributes redirect, Authentication authentication,
                                    HttpServletRequest request) {
        report(game.clearAnnouncement(authentication.getName(), ClientAddress.of(request)), redirect);
        return "redirect:/admin/game";
    }

    /** The whole settings form at once; the service owns the list of fields and their bounds. */
    @PostMapping("/config")
    public String saveConfig(@RequestParam Map<String, String> form,
                             RedirectAttributes redirect, Authentication authentication,
                             HttpServletRequest request) {
        report(game.saveGameConfig(authentication.getName(), form, ClientAddress.of(request)), redirect);
        return "redirect:/admin/game";
    }

    private static void report(AdminGameService.Result result, RedirectAttributes redirect) {
        switch (result.outcome()) {
            case SAVED -> redirect.addFlashAttribute("message",
                    "Saved. Players see it the next time they open the main menu (within a minute).");
            case CLEARED -> redirect.addFlashAttribute("message", "Cleared.");
            case NOTHING_TO_CLEAR -> redirect.addFlashAttribute("warning", "There was nothing to clear.");
            case UNCHANGED -> redirect.addFlashAttribute("warning", "Nothing changed, so nothing was saved.");
            case INVALID -> redirect.addFlashAttribute("warning", "Nothing was saved. " + result.problem());
        }
    }
}
