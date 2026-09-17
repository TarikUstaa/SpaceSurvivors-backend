package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The overview's numbers.
 *
 * <p>Asserted as <em>changes</em> — read, add, read again — never as absolute values: other test
 * classes commit players to the same database, so "there are 3 players" would depend on the order
 * the suite ran in.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminDashboardIntegrationTest {

    @Autowired
    private AdminDashboardService dashboard;

    @Autowired
    private JdbcClient db;

    @Autowired
    private MockMvc mvc;

    private UUID player(boolean test, String country) {
        return db.sql("""
                        INSERT INTO player_profile (device_id, display_name, device_secret_hash, created_by_admin, country)
                        VALUES (:d, :n, 'x', :t, :c) RETURNING player_id""")
                .param("d", "dash-" + UUID.randomUUID())
                .param("n", "Dash" + (int) (Math.random() * 1_000_000))
                .param("t", test).param("c", country)
                .query(UUID.class).single();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a real player counts as new and active; a test player counts only as a test player")
    void realAndTestAreKeptApart() {
        var before = dashboard.dashboard();

        player(false, "TR");
        player(true, "TR");

        var after = dashboard.dashboard();
        assertThat(after.players().real()).isEqualTo(before.players().real() + 1);
        assertThat(after.players().newWeek()).isEqualTo(before.players().newWeek() + 1);
        assertThat(after.players().activeDay()).isEqualTo(before.players().activeDay() + 1);
        assertThat(after.players().test()).isEqualTo(before.players().test() + 1);
        assertThat(after.signupsInWindow()).isEqualTo(before.signupsInWindow() + 1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("the signup chart always has fourteen days, empty ones included, ending today")
    void fourteenDays() {
        var days = dashboard.dashboard().signups();
        assertThat(days).hasSize(14);
        assertThat(days.getLast().date()).isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        assertThat(days).allSatisfy(day -> assertThat(day.percent()).isBetween(0, 100));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("scores of test players are counted apart from the real board")
    void testScoresApart() {
        var before = dashboard.dashboard();
        UUID test = player(true, null);
        db.sql("""
                INSERT INTO leaderboard (player_id, mode, survived_seconds, kills, reached_level, bosses_defeated)
                VALUES (:p, 'infinite', 99999, 1, 1, 0)""").param("p", test).update();

        var after = dashboard.dashboard();
        assertThat(after.testScores()).isEqualTo(before.testScores() + 1);
        // The test score is enormous; if it leaked into the real board it would be the best time.
        assertThat(after.modes()).noneMatch(m -> m.bestSeconds() >= 99999);
    }

    @Test
    @DisplayName("a bar for a small non-zero value never disappears")
    void smallValuesStayVisible() {
        assertThat(AdminDashboardService.percentOf(1, 1000)).isEqualTo(2);
        assertThat(AdminDashboardService.percentOf(0, 1000)).isZero();
        assertThat(AdminDashboardService.percentOf(5, 0)).isZero();
        assertThat(AdminDashboardService.percentOf(50, 100)).isEqualTo(50);
    }

    @Test
    @DisplayName("/admin lands on the overview; SUPPORT sees it without the backoffice panel")
    void pageAndRoles() throws Exception {
        mvc.perform(get("/admin").with(user("testadmin").roles("ADMIN")))
                .andExpect(redirectedUrl("/admin/overview"));

        mvc.perform(get("/admin/overview").with(user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("New players, last 14 days")))
                .andExpect(content().string(containsString("Refused sign-ins")));

        db.sql("INSERT INTO admin_user (username, password_hash, role) VALUES ('support-dash', 'x', 'SUPPORT')").update();
        mvc.perform(get("/admin/overview").with(user("support-dash").roles("SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Refused sign-ins"))));
    }
}
