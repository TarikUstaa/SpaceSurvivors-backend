package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test players and hand-set scores.
 *
 * <p>The test that matters most here is not about the backoffice at all: it takes a test player's
 * device id to the game's own sign-in and checks it is refused. A made-up player that somebody
 * could sign in as would be a free account for whoever guesses the id first.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminTestDataIntegrationTest {

    private static final String ADMIN = "testadmin";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    @Autowired
    private AdminPlayerService playerService;

    @Autowired
    private AdminBoardService boardService;

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private MvcResult createPlayer(String name) throws Exception {
        return mvc.perform(post("/admin/players/new").param("displayName", name)
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andReturn();
    }

    private UUID idOf(String name) {
        return db.sql("SELECT player_id FROM player_profile WHERE lower(display_name) = lower(:n)")
                .param("n", name).query(UUID.class).single();
    }

    private long playersNamed(String name) {
        return db.sql("SELECT count(*) FROM player_profile WHERE lower(display_name) = lower(:n)")
                .param("n", name).query(Long.class).single();
    }

    private MockHttpServletRequestBuilder score(UUID player, String mode, String time, String kills,
                                               String level, String bosses) {
        return post("/admin/players/" + player + "/score")
                .param("mode", mode).param("time", time).param("kills", kills)
                .param("level", level).param("bosses", bosses)
                .with(user(ADMIN).roles("ADMIN")).with(csrf());
    }

    private Map<String, Object> row(UUID player, String mode) {
        return db.sql("SELECT * FROM leaderboard WHERE player_id = :p AND mode = :m")
                .param("p", player).param("m", mode).query().singleRow();
    }

    private long scoreRows(UUID player) {
        return db.sql("SELECT count(*) FROM leaderboard WHERE player_id = :p")
                .param("p", player).query(Long.class).single();
    }

    // ── creating ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a named test player is created, flagged, audited, and shown with a TEST label")
    void createsANamedTestPlayer() throws Exception {
        MvcResult result = createPlayer("TestPilot_1");
        UUID id = idOf("TestPilot_1");

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin/players/" + id);

        Map<String, Object> profile = db.sql("SELECT * FROM player_profile WHERE player_id = :p")
                .param("p", id).query().singleRow();
        assertThat(profile.get("created_by_admin")).isEqualTo(true);
        // Never null — null is the value that would let the first caller claim the account.
        assertThat(profile.get("device_secret_hash")).isNotNull();

        long audited = db.sql("SELECT count(*) FROM admin_audit WHERE action = 'TEST_PLAYER_CREATED' AND target = :t")
                .param("t", id.toString()).query(Long.class).single();
        assertThat(audited).isEqualTo(1);

        mvc.perform(get("/admin/players").with(user(ADMIN).roles("ADMIN")))
                .andExpect(content().string(containsString("TEST")));
    }

    @Test
    @DisplayName("a blank name gets a generated one, as a new device would")
    void blankNameIsGenerated() throws Exception {
        long before = db.sql("SELECT count(*) FROM player_profile WHERE created_by_admin")
                .query(Long.class).single();

        createPlayer("   ");

        String name = db.sql("""
                        SELECT display_name FROM player_profile WHERE created_by_admin
                        ORDER BY first_login_date DESC LIMIT 1""")
                .query(String.class).single();
        assertThat(name).matches("User\\d{6}");
        assertThat(db.sql("SELECT count(*) FROM player_profile WHERE created_by_admin")
                .query(Long.class).single()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("nobody can sign in as a test player, whatever secret they present")
    void aTestPlayerCannotBeClaimed() throws Exception {
        createPlayer("Unclaimable");
        String deviceId = db.sql("SELECT device_id FROM player_profile WHERE display_name = 'Unclaimable'")
                .query(String.class).single();

        // The game's own sign-in, with the real device id. If the secret were null this would be
        // a 200 and the account would now belong to this caller.
        mvc.perform(post("/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"%s","deviceSecret":"a-device-secret-long-enough-to-matter-32"}"""
                                .formatted(deviceId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a taken name (in any case) or a malformed one creates nothing")
    void refusesBadNames() throws Exception {
        createPlayer("Taken_Name");

        mvc.perform(post("/admin/players/new").param("displayName", "taken_name")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/players"))
                .andExpect(flash().attributeExists("warning"));
        mvc.perform(post("/admin/players/new").param("displayName", "has space")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));
        mvc.perform(post("/admin/players/new").param("displayName", "ab")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));

        assertThat(playersNamed("Taken_Name")).isEqualTo(1);
        assertThat(playersNamed("has space")).isZero();
        assertThat(playersNamed("ab")).isZero();
    }

    // ── setting scores ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a score is set, then replaced by a lower one, and the audit keeps the old one")
    void setsAndLowersAScore() throws Exception {
        createPlayer("Scorer");
        UUID id = idOf("Scorer");

        mvc.perform(score(id, "infinite", "8:20", "400", "20", "3"))
                .andExpect(redirectedUrl("/admin/players/" + id))
                .andExpect(flash().attributeExists("message"));
        assertThat(((Number) row(id, "infinite").get("survived_seconds")).doubleValue()).isEqualTo(500.0);

        // Lower — a real run could never do this, and here it must.
        mvc.perform(score(id, "infinite", "60", "10", "2", "0"))
                .andExpect(flash().attributeExists("message"));
        Map<String, Object> lowered = row(id, "infinite");
        assertThat(((Number) lowered.get("survived_seconds")).doubleValue()).isEqualTo(60.0);
        assertThat(lowered.get("kills")).isEqualTo(10);

        String summary = db.sql("""
                        SELECT summary FROM admin_audit WHERE action = 'SCORE_SET' AND target = :t
                        ORDER BY audit_id DESC LIMIT 1""")
                .param("t", id.toString()).query(String.class).single();
        assertThat(summary).contains("1:00, 10 kills").contains("was 8:20, 400 kills");

        // And it is on the board the game reads, labelled for what it is.
        mvc.perform(get("/admin/leaderboard?mode=infinite").with(user(ADMIN).roles("ADMIN")))
                .andExpect(content().string(containsString("Scorer")))
                .andExpect(content().string(containsString("pill test")));
    }

    @Test
    @DisplayName("a real player's score can be set too — it is the account, not the flag, that matters")
    void setsARealPlayersScore() throws Exception {
        UUID real = db.sql("""
                        INSERT INTO player_profile (device_id, display_name, device_secret_hash)
                        VALUES (:d, 'RealOne', 'x') RETURNING player_id""")
                .param("d", "real-" + UUID.randomUUID()).query(UUID.class).single();

        mvc.perform(score(real, "campaign", "300", "100", "10", "1"))
                .andExpect(flash().attributeExists("message"));
        assertThat(scoreRows(real)).isEqualTo(1);

        // A real player's row carries no TEST label, even with a hand-set score.
        mvc.perform(get("/admin/leaderboard?mode=campaign").with(user(ADMIN).roles("ADMIN")))
                .andExpect(content().string(containsString("RealOne")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("pill test"))));
    }

    @Test
    @DisplayName("an unknown mode, a bad number or an impossible kill rate writes nothing")
    void refusesBadScores() throws Exception {
        createPlayer("BadScores");
        UUID id = idOf("BadScores");

        String[][] bad = {
                {"arcade", "100", "10", "1", "0"},      // no such mode — not silently 'infinite'
                {"infinite", "-5", "10", "1", "0"},
                {"infinite", "1:75", "10", "1", "0"},   // 75 is not a seconds field
                {"infinite", "100", "ten", "1", "0"},
                {"infinite", "100", "10", "0", "0"},    // level starts at 1
                {"infinite", "100", "7000", "1", "0"},  // 70 kills a second
                {"infinite", "100000", "10", "1", "0"}, // more than a day
        };
        for (String[] s : bad) {
            mvc.perform(score(id, s[0], s[1], s[2], s[3], s[4]))
                    .andExpect(flash().attributeExists("warning"));
        }
        assertThat(scoreRows(id)).isZero();
    }

    @Test
    @DisplayName("deleting a test player takes its scores off the board")
    void deletingRemovesScores() throws Exception {
        createPlayer("Cleanup");
        UUID id = idOf("Cleanup");
        mvc.perform(score(id, "infinite", "100", "10", "1", "0"));

        mvc.perform(post("/admin/players/" + id + "/delete").param("confirmName", "Cleanup")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/players"));

        assertThat(scoreRows(id)).isZero();
    }

    @Test
    @DisplayName("deleting all test players takes only test players, and only with the phrase")
    void deletesAllTestPlayers() throws Exception {
        createPlayer("Bulk_A");
        createPlayer("Bulk_B");
        mvc.perform(score(idOf("Bulk_A"), "infinite", "100", "10", "1", "0"));
        UUID real = db.sql("""
                        INSERT INTO player_profile (device_id, display_name, device_secret_hash)
                        VALUES (:d, 'KeepMe', 'x') RETURNING player_id""")
                .param("d", "real-" + UUID.randomUUID()).query(UUID.class).single();

        // Wrong phrase: nothing.
        mvc.perform(post("/admin/test-players/delete").param("confirmation", "delete")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));
        assertThat(playersNamed("Bulk_A")).isEqualTo(1);

        mvc.perform(post("/admin/test-players/delete").param("confirmation", "delete test players")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/players"))
                .andExpect(flash().attributeExists("message"));

        assertThat(db.sql("SELECT count(*) FROM player_profile WHERE created_by_admin")
                .query(Long.class).single()).isZero();
        assertThat(playersNamed("KeepMe")).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM leaderboard l JOIN player_profile p USING (player_id) WHERE p.created_by_admin")
                .query(Long.class).single()).isZero();
        assertThat(db.sql("SELECT count(*) FROM admin_audit WHERE action = 'TEST_PLAYERS_DELETED'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(real).isNotNull();
    }

    @Test
    @DisplayName("removing all test scores empties them from every mode and keeps players and real scores")
    void removesAllTestScores() throws Exception {
        createPlayer("Scored_A");
        UUID a = idOf("Scored_A");
        mvc.perform(score(a, "infinite", "100", "10", "1", "0"));
        mvc.perform(score(a, "campaign", "100", "10", "1", "0"));
        UUID real = db.sql("""
                        INSERT INTO player_profile (device_id, display_name, device_secret_hash)
                        VALUES (:d, 'RealScorer', 'x') RETURNING player_id""")
                .param("d", "real-" + UUID.randomUUID()).query(UUID.class).single();
        db.sql("""
                INSERT INTO leaderboard (player_id, mode, survived_seconds, kills, reached_level, bosses_defeated)
                VALUES (:p, 'infinite', 200, 20, 2, 0)""").param("p", real).update();

        mvc.perform(get("/admin/leaderboard").with(user(ADMIN).roles("ADMIN")))
                .andExpect(content().string(containsString("Remove all test scores")));

        mvc.perform(post("/admin/leaderboard/test-scores/delete").param("mode", "campaign")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/leaderboard?mode=campaign"))
                .andExpect(flash().attributeExists("message"));

        assertThat(scoreRows(a)).isZero();
        assertThat(playersNamed("Scored_A")).isEqualTo(1);
        assertThat(scoreRows(real)).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM admin_audit WHERE action = 'TEST_SCORES_REMOVED'")
                .query(Long.class).single()).isEqualTo(1);

        // Nothing left: a warning, and no second audit row.
        mvc.perform(post("/admin/leaderboard/test-scores/delete")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));
        assertThat(db.sql("SELECT count(*) FROM admin_audit WHERE action = 'TEST_SCORES_REMOVED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    // ── SUPPORT may do neither ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("SUPPORT is refused both, at the URL")
    void supportIsRefusedAtTheUrl() throws Exception {
        db.sql("INSERT INTO admin_user (username, password_hash, role) VALUES ('support-td', 'x', 'SUPPORT')")
                .update();
        createPlayer("NoSupport");
        UUID id = idOf("NoSupport");

        mvc.perform(post("/admin/players/new").param("displayName", "BySupport")
                        .with(user("support-td").roles("SUPPORT")).with(csrf()))
                .andExpect(redirectedUrl("/admin/forbidden"));
        mvc.perform(post("/admin/players/" + id + "/score")
                        .param("mode", "infinite").param("time", "100").param("kills", "1")
                        .param("level", "1").param("bosses", "0")
                        .with(user("support-td").roles("SUPPORT")).with(csrf()))
                .andExpect(redirectedUrl("/admin/forbidden"));

        assertThat(playersNamed("BySupport")).isZero();
        assertThat(scoreRows(id)).isZero();

        mvc.perform(post("/admin/test-players/delete").param("confirmation", "delete test players")
                        .with(user("support-td").roles("SUPPORT")).with(csrf()))
                .andExpect(redirectedUrl("/admin/forbidden"));
        assertThat(playersNamed("NoSupport")).isEqualTo(1);

        mvc.perform(score(id, "infinite", "100", "10", "1", "0"));
        mvc.perform(post("/admin/leaderboard/test-scores/delete")
                        .with(user("support-td").roles("SUPPORT")).with(csrf()))
                .andExpect(redirectedUrl("/admin/forbidden"));
        assertThat(scoreRows(id)).isEqualTo(1);
        mvc.perform(get("/admin/leaderboard").with(user("support-td").roles("SUPPORT")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Remove all test scores"))));

        // Not offered on the pages either.
        mvc.perform(get("/admin/players").with(user("support-td").roles("SUPPORT")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("New test player"))));
    }

    @Test
    @WithMockUser(username = "support-td", roles = "SUPPORT")
    @DisplayName("SUPPORT is refused both at the service too")
    void supportIsRefusedAtTheService() {
        assertThatThrownBy(() -> playerService.createTestPlayer("support-td", "Sneaky", null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> boardService.setScore("support-td", UUID.randomUUID(),
                "infinite", "100", "1", "1", "0", null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> boardService.removeTestScores("support-td", null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
