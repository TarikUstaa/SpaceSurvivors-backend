package com.tarikusta.spacesurvivors.admin;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The audit trail, read-only — and read-only all the way down.
 *
 * <p>There is no POST in this class and no button on the page, because there is nothing an
 * administrator may do to this table. That is not an omission to be filled in later: the
 * value of a record is exactly the difficulty of changing it, and a "clear the log" button
 * would hand the one person who might want the log gone the means to remove it.</p>
 *
 * <p>How many entries count as "recent" is {@link AdminAudit}'s to decide, not this class's —
 * it is a fact about the query, and the page only needs to be told what it was given.</p>
 */
@Controller
@RequestMapping("/admin/audit")
public class AdminAuditController {

    private final AdminAudit audit;

    public AdminAuditController(AdminAudit audit) {
        this.audit = audit;
    }

    @GetMapping
    public String audit(Model model, Authentication authentication) {
        model.addAttribute("trail", audit.recent());
        model.addAttribute("admin", authentication.getName());
        return "admin/audit";
    }
}
