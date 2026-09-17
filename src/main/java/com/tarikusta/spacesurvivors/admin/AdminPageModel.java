package com.tarikusta.spacesurvivors.admin;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * What every backoffice page needs to know about who is looking at it.
 *
 * <p>Today that is one thing: whether to draw the ADMIN-only links and buttons. It is added here,
 * once, rather than by each controller — the top bar is on every page, so every controller would
 * have had to remember, and the one that forgot would show a SUPPORT account a Users link.</p>
 *
 * <p><b>Hiding a button is not the protection.</b> {@link AdminSecurityConfig} and
 * {@code @PreAuthorize} refuse the request whether or not a button was drawn. This only stops the
 * page offering something that will be refused, which is a courtesy and nothing more.</p>
 *
 * <p>Read from the session's authorities rather than the database: {@link AdminSessionGuard} has
 * already made sure the two agree before any controller runs.</p>
 */
@ControllerAdvice(basePackages = "com.tarikusta.spacesurvivors.admin")
public class AdminPageModel {

    @ModelAttribute("isAdmin")
    public boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(AdminRole.ADMIN.authority()::equals);
    }
}
