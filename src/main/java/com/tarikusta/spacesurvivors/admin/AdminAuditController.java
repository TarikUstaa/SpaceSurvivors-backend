package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.auth.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The audit trail, read-only — and read-only all the way down.
 *
 * <p>There is no POST in this class and no button on the page, because there is nothing an
 * administrator may do to this table. That is not an omission to be filled in later: the
 * value of a record is exactly the difficulty of changing it, and a "clear the log" button
 * would hand the one person who might want the log gone the means to remove it.</p>
 *
 * <p>Filtering and the CSV export read through {@link AdminAuditSearch}, which can only
 * read — see that class for why it is not a method on the repository.</p>
 */
@Controller
@RequestMapping("/admin/audit")
public class AdminAuditController {

    private static final DateTimeFormatter FILE_DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final AdminAuditSearch search;
    private final AdminAudit audit;

    public AdminAuditController(AdminAuditSearch search, AdminAudit audit) {
        this.search = search;
        this.audit = audit;
    }

    @GetMapping
    public String audit(@RequestParam(required = false) String actor,
                        @RequestParam(required = false) String action,
                        @RequestParam(required = false) String text,
                        @RequestParam(required = false) String from,
                        @RequestParam(required = false) String to,
                        Model model, Authentication authentication) {
        AdminAuditSearch.Filter filter = AdminAuditSearch.Filter.of(actor, action, text, from, to);

        model.addAttribute("filter", filter);
        model.addAttribute("actions", AdminAction.values());
        model.addAttribute("trail", search.search(filter, AdminAuditSearch.PAGE_LIMIT));
        model.addAttribute("admin", authentication.getName());
        return "admin/audit";
    }

    /**
     * The same filter, as a CSV download.
     *
     * <p>A GET, because it changes nothing in the data — but it is written to the trail anyway.
     * The file holds sign-in addresses and the history of who was deleted; taking a copy of that
     * out of the application is exactly the kind of event this table exists to answer questions
     * about later.</p>
     */
    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam(required = false) String actor,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String text,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         Authentication authentication,
                                         HttpServletRequest request) {
        AdminAuditSearch.Filter filter = AdminAuditSearch.Filter.of(actor, action, text, from, to);
        AdminAuditSearch.Result result = search.search(filter, AdminAuditSearch.EXPORT_LIMIT);

        audit.auditExported(authentication.getName(), result.rows().size(), result.truncated(),
                filter.describe(), ClientAddress.of(request));

        String filename = "audit-" + FILE_DAY.format(Instant.now()) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                // Personal data: not to be kept by a proxy or the browser's cache.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(AuditCsv.write(result.rows(), result.truncated()));
    }
}
