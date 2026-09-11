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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The player detail page, and the only irreversible thing in the backoffice.
 *
 * <p>Half of these tests exist to prove a delete did <em>not</em> happen. That is the right
 * proportion for a button that destroys somebody's save: the case where it works is one line,
 * and every way it could fire when it should not is worth its own.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminPlayerDetailIntegrationTest {

    private static final String ADMIN = "testadmin";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    private UUID player;
    private String name;

    @BeforeEach
    void seedAFullPlayer() {
        name = "Detail" + (int) (Math.random() * 100_000);
        player = db.sql("""
                        INSERT INTO player_profile (device_id, display_name, last_ip)
                        VALUES (:device, :name, cast('203.0.113.7' as inet))
                        RETURNING player_id""")
                .param("device", "detail-test-" + UUID.randomUUID())
                .param("name", name)
                .query(UUID.class).single();

        db.sql("""
                        INSERT INTO player_progress (player_id, progress_data)
                        VALUES (:p, cast('{"wallet":4242,"runsPlayed":9}' as jsonb))""")
                .param("p", player).update();

        db.sql("""
                        INSERT INTO leaderboard (player_id, mode, survived_seconds, kills,
                                                 reached_level, bosses_defeated)
                        VALUES (:p, 'infinite', 500, 80, 20, 3)""")
                .param("p", player).update();
    }

    private long countIn(String table) {
        return db.sql("SELECT count(*) FROM " + table + " WHERE player_id = :p")
                .param("p", player).query(Long.class).single();
    }

    // ── reading ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the page shows the account, the save and the scores")
    void showsEverything() throws Exception {
        mvc.perform(get("/admin/players/" + player).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(name)))
                .andExpect(content().string(containsString("203.0.113.7")))
                // The save is shown as stored, so a value from inside the JSON is the proof
                // that it was actually read and not merely counted.
                .andExpect(content().string(containsString("4242")))
                .andExpect(content().string(containsString("8:20")));
    }

    @Test
    @DisplayName("an id that is not a player answers 404")
    void unknownPlayerIs404() throws Exception {
        mvc.perform(get("/admin/players/" + UUID.randomUUID()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    // ── deleting ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the typed name deletes the player, the save and the scores together")
    void deletesEverything() throws Exception {
        mvc.perform(post("/admin/players/" + player + "/delete")
                        .param("confirmName", name)
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/players"));

        assertThat(countIn("player_profile")).isZero();
        // Neither of these was named in the statement. They are gone because of ON DELETE
        // CASCADE on their foreign keys — worth asserting, because that rule lives in a
        // migration written months ago and nothing in the Java would notice if it changed.
        assertThat(countIn("player_progress")).isZero();
        assertThat(countIn("leaderboard")).isZero();
    }

    @Test
    @DisplayName("a name that does not match deletes nothing")
    void refusesAMistypedName() throws Exception {
        // The assertion the whole confirmation exists for. A browser dialog cannot make this
        // promise — only a check on the server can, because only the server sees the request.
        mvc.perform(post("/admin/players/" + player + "/delete")
                        .param("confirmName", "Somebody Else")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/players/" + player));

        assertThat(countIn("player_profile")).isEqualTo(1);
        assertThat(countIn("player_progress")).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty confirmation deletes nothing")
    void refusesAnEmptyConfirmation() throws Exception {
        // Its own test because empty is the value a form submitted by something other than a
        // person is most likely to carry — and the comparison is written the way round that
        // makes an empty string a mismatch rather than a match against a null.
        mvc.perform(post("/admin/players/" + player + "/delete")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(countIn("player_profile")).isEqualTo(1);
    }

    @Test
    @DisplayName("a delete without a CSRF token deletes nothing")
    void refusesWithoutCsrf() throws Exception {
        mvc.perform(post("/admin/players/" + player + "/delete")
                        .param("confirmName", name)
                        .with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isForbidden());

        assertThat(countIn("player_profile")).isEqualTo(1);
    }

    @Test
    @DisplayName("an anonymous delete deletes nothing, correct name or not")
    void refusesAnonymous() throws Exception {
        mvc.perform(post("/admin/players/" + player + "/delete")
                        .param("confirmName", name)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(countIn("player_profile")).isEqualTo(1);
    }
}
