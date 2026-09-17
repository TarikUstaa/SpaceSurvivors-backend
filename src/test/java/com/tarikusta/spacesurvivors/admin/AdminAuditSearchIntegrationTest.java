package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Filtering the audit trail and exporting it. */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminAuditSearchIntegrationTest {

    @Autowired
    private AdminAuditSearch search;

    @Autowired
    private JdbcClient db;

    @Autowired
    private MockMvc mvc;

    private void entry(String actor, String action, String summary, String when) {
        db.sql("""
                        INSERT INTO admin_audit (happened_at, actor, action, target, summary)
                        VALUES (cast(:w as timestamptz), :a, :act, 'tgt-zq', :s)""")
                .param("w", when).param("a", actor).param("act", action).param("s", summary)
                .update();
    }

    @BeforeEach
    void seed() {
        entry("zq_alice", "SCORE_REMOVED", "removed the infinite score", "2026-03-01 10:00:00+00");
        entry("zq_alice", "PLAYER_DELETED", "deleted player 'Zq_Villain'", "2026-03-02 23:59:59+00");
        entry("zq_bob", "SIGN_IN_FAILED", "sign-in refused", "2026-03-03 00:00:00+00");
    }

    private List<String> summaries(AdminAuditSearch.Filter filter) {
        return search.search(filter, 200).rows().stream().map(AdminAuditSearch.Row::summary).toList();
    }

    @Test
    @DisplayName("filters by who, what, text and an inclusive UTC day range")
    void filters() {
        assertThat(summaries(AdminAuditSearch.Filter.of("ZQ_ALICE", null, null, null, null))).hasSize(2);
        assertThat(summaries(AdminAuditSearch.Filter.of("zq_", "SIGN_IN_FAILED", null, null, null)))
                .containsExactly("sign-in refused");
        assertThat(summaries(AdminAuditSearch.Filter.of(null, null, "zq_villain", null, null)))
                .containsExactly("deleted player 'Zq_Villain'");
        // "to" includes the whole of 2 March, up to 23:59:59, and nothing of 3 March.
        assertThat(summaries(AdminAuditSearch.Filter.of("zq_", null, null, "2026-03-02", "2026-03-02")))
                .containsExactly("deleted player 'Zq_Villain'");
    }

    @Test
    @DisplayName("an unusable action or date is dropped, not an error")
    void junkIsIgnored() {
        var filter = AdminAuditSearch.Filter.of("zq_", "DROP TABLE", null, "yesterday", "2026-13-40");
        assertThat(filter.action()).isNull();
        assertThat(filter.from()).isNull();
        assertThat(filter.to()).isNull();
        assertThat(summaries(filter)).hasSize(3);
    }

    @Test
    @DisplayName("the page filters, and says how many matched")
    void page() throws Exception {
        mvc.perform(get("/admin/audit").param("actor", "zq_bob").with(user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("sign-in refused")))
                .andExpect(content().string(containsString("<span>1</span> matching")));
    }

    @Test
    @DisplayName("the export is a CSV attachment, and the export itself is written to the trail")
    void exports() throws Exception {
        String csv = mvc.perform(get("/admin/audit/export").param("actor", "zq_alice")
                        .with(user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv.split("\r\n")).hasSize(3);   // header + two rows
        assertThat(csv).contains("\"deleted player 'Zq_Villain'\"");

        String recorded = db.sql("SELECT summary FROM admin_audit WHERE action = 'AUDIT_EXPORTED' ORDER BY audit_id DESC LIMIT 1")
                .query(String.class).single();
        assertThat(recorded).contains("exported 2 audit entries").contains("actor 'zq_alice'");
    }

    @Test
    @DisplayName("a cell a spreadsheet would run as a formula is neutralised")
    void formulaInjection() {
        // The actor of a refused sign-in is whatever a stranger typed into the login form.
        assertThat(AuditCsv.cell("=HYPERLINK(\"http://x\")")).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(AuditCsv.cell("+1")).isEqualTo("\"'+1\"");
        assertThat(AuditCsv.cell("-1")).isEqualTo("\"'-1\"");
        assertThat(AuditCsv.cell("@SUM")).isEqualTo("\"'@SUM\"");
        assertThat(AuditCsv.cell("plain, with comma")).isEqualTo("\"plain, with comma\"");
        assertThat(AuditCsv.cell(null)).isEqualTo("\"\"");
    }

    @Test
    @DisplayName("SUPPORT cannot export")
    void supportCannotExport() throws Exception {
        db.sql("INSERT INTO admin_user (username, password_hash, role) VALUES ('support-au', 'x', 'SUPPORT')").update();
        mvc.perform(get("/admin/audit/export").with(user("support-au").roles("SUPPORT")))
                .andExpect(redirectedUrl("/admin/forbidden"));
    }
}
