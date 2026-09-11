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
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The first page in the backoffice that changes something, and the tests are mostly about
 * what it must <em>not</em> change.
 *
 * <p>Every assertion here reads the database afterwards rather than trusting the response.
 * A redirect to the right place with the right message is what a broken delete looks like
 * too — the status code is the controller agreeing with itself, and the row is the fact.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminLeaderboardIntegrationTest {

    private static final String ADMIN = "testadmin";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    private UUID player;

    @BeforeEach
    void seedAPlayerOnBothBoards() {
        player = db.sql("""
                        INSERT INTO player_profile (device_id, display_name)
                        VALUES (:device, :name)
                        RETURNING player_id""")
                .param("device", "board-test-" + UUID.randomUUID())
                .param("name", "Boarder" + (int) (Math.random() * 100_000))
                .query(UUID.class).single();

        insertScore("infinite", 620f);
        insertScore("campaign", 310f);
    }

    private void insertScore(String mode, float seconds) {
        db.sql("""
                        INSERT INTO leaderboard (player_id, mode, survived_seconds, kills,
                                                 reached_level, bosses_defeated)
                        VALUES (:player, :mode, :seconds, 100, 12, 2)""")
                .param("player", player)
                .param("mode", mode)
                .param("seconds", seconds)
                .update();
    }

    private long scoresFor(UUID playerId) {
        return db.sql("SELECT count(*) FROM leaderboard WHERE player_id = :p")
                .param("p", playerId).query(Long.class).single();
    }

    private boolean stillExists(UUID playerId, String mode) {
        return db.sql("SELECT count(*) FROM leaderboard WHERE player_id = :p AND mode = :m")
                .param("p", playerId).param("m", mode)
                .query(Long.class).single() > 0;
    }

    // ── reading ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the board lists the mode that was asked for, and not the other one")
    void listsOneMode() throws Exception {
        // Both rows belong to the same player, so a page that ignored the mode would still
        // show a plausible name. The times are what tell the two apart: 10:20 and 5:10.
        mvc.perform(get("/admin/leaderboard").param("mode", "infinite")
                        .with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("10:20")))
                .andExpect(content().string(not(containsString("5:10"))));
    }

    @Test
    @DisplayName("a mode nobody has heard of falls back instead of failing")
    void toleratesAnUnknownMode() throws Exception {
        // The value arrives in a query string, so it can be anything. A 500 here would be a
        // page an administrator can break by mistyping a URL.
        mvc.perform(get("/admin/leaderboard").param("mode", "'; DROP TABLE leaderboard--")
                        .with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("10:20")));
    }

    // ── removing ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("removing a score takes that row and leaves the other mode alone")
    void removesOneScore() throws Exception {
        mvc.perform(post("/admin/leaderboard/delete")
                        .param("playerId", player.toString())
                        .param("mode", "infinite")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/leaderboard?mode=infinite"));

        assertThat(stillExists(player, "infinite")).isFalse();
        // The half that a delete-by-player-id would have got wrong.
        assertThat(stillExists(player, "campaign")).isTrue();
    }

    @Test
    @DisplayName("removing a score does not remove the player or their save")
    void keepsThePlayer() throws Exception {
        db.sql("""
                        INSERT INTO player_progress (player_id, progress_data)
                        VALUES (:p, cast('{"wallet":1}' as jsonb))""")
                .param("p", player).update();

        mvc.perform(post("/admin/leaderboard/delete")
                        .param("playerId", player.toString())
                        .param("mode", "infinite")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // "This score should not be there" and "this person should not be here" are different
        // decisions. A cascade that quietly turned the first into the second would delete
        // somebody's hours of progress because they once posted an implausible run.
        Long profiles = db.sql("SELECT count(*) FROM player_profile WHERE player_id = :p")
                .param("p", player).query(Long.class).single();
        Long saves = db.sql("SELECT count(*) FROM player_progress WHERE player_id = :p")
                .param("p", player).query(Long.class).single();

        assertThat(profiles).isEqualTo(1);
        assertThat(saves).isEqualTo(1);
    }

    @Test
    @DisplayName("removing a score that is already gone says so rather than claiming success")
    void reportsAMissingRow() throws Exception {
        db.sql("DELETE FROM leaderboard WHERE player_id = :p AND mode = 'infinite'")
                .param("p", player).update();

        mvc.perform(post("/admin/leaderboard/delete")
                        .param("playerId", player.toString())
                        .param("mode", "infinite")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("warning"));
    }

    // ── who may remove ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous request cannot remove a score")
    void refusesAnonymousRemoval() throws Exception {
        mvc.perform(post("/admin/leaderboard/delete")
                        .param("playerId", player.toString())
                        .param("mode", "infinite")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        // The assertion that matters. A redirect to the login page proves where the browser
        // was sent, not that the delete was refused — only the row proves that.
        assertThat(scoresFor(player)).isEqualTo(2);
    }

    @Test
    @DisplayName("a removal without a CSRF token is refused, and nothing is deleted")
    void refusesRemovalWithoutCsrf() throws Exception {
        // The attack this stops: a page on another site containing a form that posts here.
        // The administrator's session cookie rides along automatically; the token does not,
        // because that other page has no way to read it.
        mvc.perform(post("/admin/leaderboard/delete")
                        .param("playerId", player.toString())
                        .param("mode", "infinite")
                        .with(user(ADMIN).roles("ADMIN")))
                .andExpect(redirectedUrl("/admin/login?expired"));

        assertThat(scoresFor(player)).isEqualTo(2);
    }
}
