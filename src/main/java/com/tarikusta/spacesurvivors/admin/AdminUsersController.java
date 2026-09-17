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

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * The accounts page. ADMIN only — by URL rule in {@link AdminSecurityConfig}, and again on every
 * method of {@link AdminUserService}.
 *
 * <p>Same shape as every other backoffice controller: each POST hands its form to the service
 * and turns the outcome into a sentence, and every POST ends in a redirect.</p>
 *
 * <p><b>The temporary password travels as a flash attribute</b> — held in the session for exactly
 * one redirect, then gone. Putting it in the redirect URL instead would write it into the browser
 * history, the server's access log and any proxy in between.</p>
 */
@Controller
@RequestMapping("/admin/users")
public class AdminUsersController {

    private final AdminUserService users;

    public AdminUsersController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    public String list(Model model, Authentication authentication) {
        model.addAttribute("accounts", users.accounts());
        model.addAttribute("roles", Arrays.asList(AdminRole.values()));
        model.addAttribute("admin", authentication.getName());
        return "admin/users";
    }

    @PostMapping
    public String create(@RequestParam(required = false) String username,
                         @RequestParam(required = false) String role,
                         RedirectAttributes redirect, Authentication authentication,
                         HttpServletRequest request) {
        report(users.create(authentication.getName(), username, role, ClientAddress.of(request)),
                "Created", redirect);
        return "redirect:/admin/users";
    }

    @PostMapping("/{accountId}/role")
    public String role(@PathVariable UUID accountId,
                       @RequestParam(required = false) String role,
                       RedirectAttributes redirect, Authentication authentication,
                       HttpServletRequest request) {
        report(users.changeRole(authentication.getName(), accountId, role,
                ClientAddress.of(request)), "Changed the role of", redirect);
        return "redirect:/admin/users";
    }

    @PostMapping("/{accountId}/enabled")
    public String enabled(@PathVariable UUID accountId,
                          @RequestParam boolean enabled,
                          RedirectAttributes redirect, Authentication authentication,
                          HttpServletRequest request) {
        report(users.setEnabled(authentication.getName(), accountId, enabled,
                ClientAddress.of(request)), enabled ? "Re-enabled" : "Disabled", redirect);
        return "redirect:/admin/users";
    }

    @PostMapping("/{accountId}/password")
    public String resetPassword(@PathVariable UUID accountId,
                                RedirectAttributes redirect, Authentication authentication,
                                HttpServletRequest request) {
        report(users.resetPassword(authentication.getName(), accountId,
                ClientAddress.of(request)), "Reset the password of", redirect);
        return "redirect:/admin/users";
    }

    @PostMapping("/{accountId}/two-factor/reset")
    public String resetTwoFactor(@PathVariable UUID accountId,
                                 RedirectAttributes redirect, Authentication authentication,
                                 HttpServletRequest request) {
        report(users.resetTwoFactor(authentication.getName(), accountId,
                ClientAddress.of(request)), "Removed two-factor sign-in from", redirect);
        return "redirect:/admin/users";
    }

    /** One switch for every action, so the sentences for the shared refusals are written once. */
    private static void report(AdminUserService.Result result, String verb,
                               RedirectAttributes redirect) {
        switch (result.outcome()) {
            case DONE -> {
                redirect.addFlashAttribute("message", verb + " '" + result.username() + "'.");
                if (result.temporaryPassword() != null) {
                    redirect.addFlashAttribute("issued", Map.of(
                            "username", result.username(),
                            "password", result.temporaryPassword()));
                }
            }
            case UNCHANGED -> redirect.addFlashAttribute("warning",
                    "Nothing changed — '" + result.username() + "' already was that.");
            case INVALID_USERNAME -> redirect.addFlashAttribute("warning",
                    "Usernames are 3–32 characters: letters, digits, dot, underscore, hyphen.");
            case USERNAME_TAKEN -> redirect.addFlashAttribute("warning",
                    "An account called '" + result.username() + "' already exists.");
            case UNKNOWN_ROLE -> redirect.addFlashAttribute("warning", "That is not a role.");
            case NOT_ON_YOURSELF -> redirect.addFlashAttribute("warning",
                    "You cannot change your own account here. Another administrator has to — "
                    + "that is what stops the backoffice locking itself out.");
            case NO_SUCH_ACCOUNT -> redirect.addFlashAttribute("warning",
                    "That account no longer exists.");
        }
    }
}
