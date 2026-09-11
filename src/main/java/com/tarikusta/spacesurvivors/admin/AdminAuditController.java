package com.tarikusta.spacesurvivors.admin;

import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * The audit trail, read-only — and read-only all the way down.
 *
 * <p>There is no POST in this class and no button on the page, because there is nothing an
 * administrator may do to this table. That is not an omission to be filled in later: the
 * value of a record is exactly the difficulty of changing it, and a "clear the log" button
 * would hand the one person who might want the log gone the means to remove it.</p>
 *
 * <p>The page shows a fixed number of recent entries and says how many there are in total,
 * rather than pretending to show everything. Growing past that is the point at which this
 * screen wants the same pagination the player list is waiting for.</p>
 */
@Controller
@RequestMapping("/admin/audit")
public class AdminAuditController {

    /**
     * How many rows the page draws. Large enough that a day's work fits, small enough that
     * the page stays one query and one screenful of HTML.
     */
    private static final int RECENT = 200;

    private final AdminAuditRepository entries;

    public AdminAuditController(AdminAuditRepository entries) {
        this.entries = entries;
    }

    @GetMapping
    public String audit(Model model, Authentication authentication) {
        List<AdminAuditEntry> recent =
                entries.findAllByOrderByHappenedAtDescAuditIdDesc(Limit.of(RECENT));

        model.addAttribute("entries", recent);
        model.addAttribute("total", entries.count());
        model.addAttribute("shown", recent.size());
        model.addAttribute("limit", RECENT);
        model.addAttribute("admin", authentication.getName());
        return "admin/audit";
    }
}
