package com.tarikusta.spacesurvivors.admin;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The overview page — where a sign-in lands. Both roles. */
@Controller
public class AdminDashboardController {

    private final AdminDashboardService dashboard;

    public AdminDashboardController(AdminDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/admin/overview")
    public String overview(Model model, Authentication authentication) {
        model.addAttribute("d", dashboard.dashboard());
        model.addAttribute("admin", authentication.getName());
        return "admin/overview";
    }
}
