package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * Renaming a player from the backoffice — and, as much as the rename, what it must not change.
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminRenameIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    private UUID player;

    @BeforeEach
    void seed() {
        // Last seen well in the past, set on insert (the trigger only runs on UPDATE).
        player = db.sql("""
                        INSERT INTO player_profile (device_id, display_name, device_secret_hash, updated_at)
                        VALUES (:d, 'Rude_Name', 'x', now() - interval '40 days') RETURNING player_id""")
                .param("d", "rename-" + UUID.randomUUID()).query(UUID.class).single();
        db.sql("""
                INSERT INTO player_profile (device_id, display_name, device_secret_hash)
                VALUES (:d, 'Occupied', 'x')""").param("d", "rename-" + UUID.randomUUID()).update();
    }

    private MockHttpServletRequestBuilder rename(String name, String role) {
        return post("/admin/players/" + player + "/rename").param("displayName", name)
                .with(user(role.equals("ADMIN") ? "testadmin" : "support-rn").roles(role)).with(csrf());
    }

    private String name() {
        return db.sql("SELECT display_name FROM player_profile WHERE player_id = :p")
                .param("p", player).query(String.class).single();
    }

    private OffsetDateTime lastSeen() {
        return db.sql("SELECT updated_at FROM player_profile WHERE player_id = :p")
                .param("p", player).query(OffsetDateTime.class).single();
    }

    @Test
    @DisplayName("a rename is written and audited, and does not make the player look active")
    void renames() throws Exception {
        OffsetDateTime before = lastSeen();

        mvc.perform(rename("Polite_Name", "ADMIN"))
                .andExpect(redirectedUrl("/admin/players/" + player))
                .andExpect(flash().attributeExists("message"));

        assertThat(name()).isEqualTo("Polite_Name");
        assertThat(lastSeen()).isEqualTo(before);

        String summary = db.sql("SELECT summary FROM admin_audit WHERE action = 'PLAYER_RENAMED' AND target = :t")
                .param("t", player.toString()).query(String.class).single();
        assertThat(summary).contains("'Rude_Name' to 'Polite_Name'");
    }

    @Test
    @DisplayName("SUPPORT may rename — it is moderation")
    void supportRenames() throws Exception {
        db.sql("INSERT INTO admin_user (username, password_hash, role) VALUES ('support-rn', 'x', 'SUPPORT')").update();

        mvc.perform(rename("Fine_Name", "SUPPORT")).andExpect(flash().attributeExists("message"));
        assertThat(name()).isEqualTo("Fine_Name");
    }

    @Test
    @DisplayName("a taken name (any case), a malformed one, or the same one changes nothing")
    void refuses() throws Exception {
        mvc.perform(rename("occupied", "ADMIN")).andExpect(flash().attributeExists("warning"));
        mvc.perform(rename("bad name!", "ADMIN")).andExpect(flash().attributeExists("warning"));
        mvc.perform(rename("Rude_Name", "ADMIN")).andExpect(flash().attributeExists("warning"));

        assertThat(name()).isEqualTo("Rude_Name");
        assertThat(db.sql("SELECT count(*) FROM admin_audit WHERE action = 'PLAYER_RENAMED' AND target = :t")
                .param("t", player.toString()).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("changing only the capitalisation of the player's own name is allowed")
    void caseOnlyChange() throws Exception {
        mvc.perform(rename("rude_name", "ADMIN")).andExpect(flash().attributeExists("message"));
        assertThat(name()).isEqualTo("rude_name");
    }

    @Test
    @DisplayName("an ordinary update still moves last seen — the flag is local to its transaction")
    void triggerStillWorksWithoutTheFlag() {
        OffsetDateTime before = lastSeen();
        db.sql("UPDATE player_profile SET country = 'TR' WHERE player_id = :p").param("p", player).update();
        assertThat(lastSeen()).isAfter(before);
    }
}
