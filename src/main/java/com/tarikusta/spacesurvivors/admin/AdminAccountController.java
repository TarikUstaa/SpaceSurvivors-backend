package com.tarikusta.spacesurvivors.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The administrator's own account page — which today means one thing: their password.
 *
 * <p>It exists before the backoffice is deployed rather than after, because until it does,
 * the only way to change an administrator's password is to write SQL against the live
 * database. An account whose credential cannot be rotated is an account that stays
 * compromised.</p>
 */
@Controller
@RequestMapping("/admin/password")
public class AdminAccountController {

    private final AdminAccountService accounts;
    private final AdminAudit audit;

    public AdminAccountController(AdminAccountService accounts, AdminAudit audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    @GetMapping
    public String form(Model model, Authentication authentication) {
        model.addAttribute("admin", authentication.getName());
        model.addAttribute("minLength", accounts.minimumPasswordLength());
        return "admin/password";
    }

    /**
     * Every outcome ends in a redirect, including the failures.
     *
     * <p>Rendering the form again after a POST leaves the browser one refresh away from
     * offering to submit it a second time — with a password in it. Redirecting means the page
     * the administrator is looking at afterwards was fetched with a GET and holds nothing.</p>
     */
    /**
     * {@code @Transactional} so that the change and its audit entry are one unit.
     * {@link AdminAccountService#changePassword} has its own {@code @Transactional} and joins
     * this one rather than opening a second; without the annotation here the password would
     * already be committed by the time the audit row was attempted, and a failure would leave
     * a changed credential nobody has a record of.
     */
    @PostMapping
    @Transactional
    public String change(@RequestParam(required = false) String currentPassword,
                         @RequestParam(required = false) String newPassword,
                         @RequestParam(required = false) String confirmPassword,
                         RedirectAttributes redirect,
                         Authentication authentication,
                         HttpServletRequest request) {

        // Checked here rather than in the service: "you typed it twice differently" is a fact
        // about the form, not about the account. The service never sees the second copy.
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            redirect.addFlashAttribute("warning", "The two new passwords did not match.");
            return "redirect:/admin/password";
        }

        AdminAccountService.Result result =
                accounts.changePassword(authentication.getName(), currentPassword, newPassword);

        switch (result) {
            case CHANGED -> {
                // Only this branch is audited. A rejected attempt changed nothing, and the
                // one that matters — somebody else's session being turned into permanent
                // ownership of the account — is a CHANGED row with an actor and an address.
                audit.passwordChanged(authentication.getName(), request);
                redirect.addFlashAttribute("message",
                        "Password changed. It is the one to use next time you sign in.");
            }
            case WRONG_CURRENT_PASSWORD -> redirect.addFlashAttribute("warning",
                    "That is not your current password.");
            case TOO_SHORT -> redirect.addFlashAttribute("warning",
                    "The new password must be at least "
                    + accounts.minimumPasswordLength() + " characters.");
            case SAME_AS_CURRENT -> redirect.addFlashAttribute("warning",
                    "That is already your password.");
            // Only reachable if the account was deleted while its session was open — rare,
            // but a blank success message would be the worst possible answer to it.
            case NO_SUCH_ADMIN -> redirect.addFlashAttribute("warning",
                    "This account no longer exists. Sign out and back in.");
        }

        return "redirect:/admin/password";
    }
}
