package com.tarikusta.spacesurvivors.game;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The main-menu announcement: published in the backoffice, read by the game without a token. */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AnnouncementIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM app_setting WHERE key = 'announcement'").update();
    }

    @Test
    @DisplayName("nothing published: 204, no token needed, cacheable for a minute")
    void noneIs204() throws Exception {
        mvc.perform(get("/v1/announcement"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("max-age=60")));
    }

    @Test
    @DisplayName("a published announcement is what the game reads, with whitespace collapsed")
    void publishAndRead() throws Exception {
        mvc.perform(post("/admin/game/announcement")
                        .param("message", "  Maintenance\n\ttonight   at 22:00  ").param("level", "WARNING")
                        .with(user("testadmin").roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/game"))
                .andExpect(flash().attributeExists("message"));

        mvc.perform(get("/v1/announcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Maintenance tonight at 22:00"))
                .andExpect(jsonPath("$.level").value("warning"));

        assertThat(db.sql("SELECT count(*) FROM admin_audit WHERE action = 'ANNOUNCEMENT_SET'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("clearing takes it down; clearing again says there was nothing")
    void clear() throws Exception {
        mvc.perform(post("/admin/game/announcement").param("message", "Hello").param("level", "info")
                .with(user("testadmin").roles("ADMIN")).with(csrf()));

        mvc.perform(post("/admin/game/announcement/clear").with(user("testadmin").roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("message"));
        mvc.perform(get("/v1/announcement")).andExpect(status().isNoContent());

        mvc.perform(post("/admin/game/announcement/clear").with(user("testadmin").roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));
    }

    @Test
    @DisplayName("an empty, oversized or wrongly levelled message changes nothing")
    void refusesBadInput() throws Exception {
        for (String[] bad : new String[][]{{"   ", "info"}, {"x".repeat(281), "info"}, {"Hello", "urgent"}}) {
            mvc.perform(post("/admin/game/announcement").param("message", bad[0]).param("level", bad[1])
                            .with(user("testadmin").roles("ADMIN")).with(csrf()))
                    .andExpect(flash().attributeExists("warning"));
        }
        mvc.perform(get("/v1/announcement")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("a malformed row typed into the database reads as nothing, not an error")
    void malformedRowIsIgnored() throws Exception {
        db.sql("INSERT INTO app_setting (key, value, updated_by) VALUES ('announcement', '{\"level\":\"loud\"}', 'psql')")
                .update();
        mvc.perform(get("/v1/announcement")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("SUPPORT cannot publish")
    void supportCannotPublish() throws Exception {
        db.sql("INSERT INTO admin_user (username, password_hash, role) VALUES ('support-an', 'x', 'SUPPORT')").update();
        mvc.perform(post("/admin/game/announcement").param("message", "Hi").param("level", "info")
                        .with(user("support-an").roles("SUPPORT")).with(csrf()))
                .andExpect(redirectedUrl("/admin/forbidden"));
        mvc.perform(get("/v1/announcement")).andExpect(status().isNoContent());
    }
}
