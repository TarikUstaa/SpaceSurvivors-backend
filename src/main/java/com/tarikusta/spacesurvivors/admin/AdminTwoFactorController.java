package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.auth.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The second step of signing in, and the page where an account turns two-factor on or off.
 *
 * <p>The secret being enrolled lives in the session until a code from it checks out, and nowhere
 * else — not in a hidden form field, where it would be one "view source" or one proxy log away from
 * whoever the page passed through.</p>
 */
@Controller
public class AdminTwoFactorController {

    static final String ENROLLING = "admin.twoFactorEnrolling";

    private final AdminTwoFactorService twoFactor;

    public AdminTwoFactorController(AdminTwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
    }

    // ── the second step of a sign-in ───────────────────────────────────────────────────

    @GetMapping("/admin/2fa")
    public String challenge(HttpServletRequest request) {
        if (!pending(request)) {
            return "redirect:/admin/overview";
        }
        return "admin/two-factor";
    }

    /**
     * On success the session id changes again, as it did at the password step: a session id seen
     * between the two steps must not be the one that ends up fully signed in.
     */
    @PostMapping("/admin/2fa")
    public String verify(@RequestParam(required = false) String code,
                         Authentication authentication, HttpServletRequest request) {
        if (!pending(request)) {
            return "redirect:/admin/overview";
        }
        if (!twoFactor.verifySignIn(authentication.getName(), code, ClientAddress.of(request))) {
            return "redirect:/admin/2fa?error";
        }
        request.getSession().removeAttribute(AdminSessionGuard.TWO_FACTOR_PENDING);
        request.changeSessionId();
        return "redirect:/admin/overview";
    }

    // ── turning it on and off ──────────────────────────────────────────────────────────

    @GetMapping("/admin/security")
    public String security(Model model, Authentication authentication, HttpServletRequest request) {
        AdminTwoFactorService.Status status = twoFactor.status(authentication.getName());
        Object enrolling = request.getSession().getAttribute(ENROLLING);

        model.addAttribute("status", status);
        if (!status.enabled() && enrolling instanceof AdminTwoFactorService.Enrollment enrollment) {
            model.addAttribute("enrollment", enrollment);
        }
        model.addAttribute("admin", authentication.getName());
        return "admin/security";
    }

    @PostMapping("/admin/security/two-factor/begin")
    public String begin(Authentication authentication, HttpServletRequest request) {
        request.getSession().setAttribute(ENROLLING, twoFactor.begin(authentication.getName()));
        return "redirect:/admin/security";
    }

    @PostMapping("/admin/security/two-factor/cancel")
    public String cancel(HttpServletRequest request) {
        request.getSession().removeAttribute(ENROLLING);
        return "redirect:/admin/security";
    }

    @PostMapping("/admin/security/two-factor/enable")
    public String enable(@RequestParam(required = false) String currentPassword,
                         @RequestParam(required = false) String code,
                         RedirectAttributes redirect, Authentication authentication,
                         HttpServletRequest request) {
        HttpSession session = request.getSession();
        String secret = session.getAttribute(ENROLLING) instanceof AdminTwoFactorService.Enrollment e
                ? e.secret() : null;

        AdminTwoFactorService.Result result = twoFactor.enable(authentication.getName(),
                currentPassword, secret, code, ClientAddress.of(request));

        switch (result.outcome()) {
            case DONE -> {
                session.removeAttribute(ENROLLING);
                redirect.addFlashAttribute("message", "Two-factor sign-in is on.");
                redirect.addFlashAttribute("recoveryCodes", result.recoveryCodes());
            }
            case WRONG_PASSWORD -> redirect.addFlashAttribute("warning", "That is not your password.");
            case WRONG_CODE -> redirect.addFlashAttribute("warning", "That code did not match. "
                    + "Check the app shows SpaceSurvivors and try the current code.");
            case ALREADY_ON -> redirect.addFlashAttribute("warning", "Two-factor is already on.");
            default -> redirect.addFlashAttribute("warning", "Sign out and back in.");
        }
        return "redirect:/admin/security";
    }

    @PostMapping("/admin/security/two-factor/disable")
    public String disable(@RequestParam(required = false) String currentPassword,
                          @RequestParam(required = false) String code,
                          RedirectAttributes redirect, Authentication authentication,
                          HttpServletRequest request) {
        AdminTwoFactorService.Result result = twoFactor.disable(authentication.getName(),
                currentPassword, code, ClientAddress.of(request));

        switch (result.outcome()) {
            case DONE -> redirect.addFlashAttribute("message", "Two-factor sign-in is off.");
            case WRONG_PASSWORD -> redirect.addFlashAttribute("warning", "That is not your password.");
            case WRONG_CODE -> redirect.addFlashAttribute("warning",
                    "That code did not match. Turning two-factor off needs a code from the app.");
            case NOT_ON -> redirect.addFlashAttribute("warning", "Two-factor is already off.");
            default -> redirect.addFlashAttribute("warning", "Sign out and back in.");
        }
        return "redirect:/admin/security";
    }

    private static boolean pending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null
               && Boolean.TRUE.equals(session.getAttribute(AdminSessionGuard.TWO_FACTOR_PENDING));
    }
}
