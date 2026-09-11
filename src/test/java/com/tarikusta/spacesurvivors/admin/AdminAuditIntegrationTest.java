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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit trail, tested the only way an audit trail can be: by doing the thing and then
 * reading the table.
 *
 * <p>Two questions run through every test here, and they are different questions. <b>Is what
 * happened written down?</b> — and, just as importantly, <b>is what did not happen left
 * alone?</b> A log that records refused attempts as though they were actions is worse than no
 * log, because it is read as one and it lies. Half the tests below are about the second
 * question.</p>
 *
 * <p>Nothing here asserts on a flash message or a redirect. Those say what the controller
 * believes; the row says what the database will still be holding tomorrow.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminAuditIntegrationTest {

    private static final String ADMIN = "testadmin";
    private static final String PASSWORD = "test-admin-password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    private UUID player;
    private String playerName;
    private long watermark;

    @BeforeEach
    void seedAPlayerWithAScore() {
        // Where the table stood before this test ran. It is not empty: other classes in this
        // context sign in for real, and they are not all @Transactional, so their entries are
        // committed and stay — which is exactly what an append-only table is supposed to do.
        //
        // "Everything this test caused" is therefore "everything after this mark", and being
        // able to say that is the monotonic key from V4 earning its keep. A uuid key would
        // have left no way to express it short of emptying somebody else's rows.
        watermark = db.sql("SELECT coalesce(max(audit_id), 0) FROM admin_audit")
                .query(Long.class).single();

        playerName = "Audited" + (int) (Math.random() * 100_000);
        player = db.sql("""
                        INSERT INTO player_profile (device_id, display_name)
                        VALUES (:device, :name)
                        RETURNING player_id""")
                .param("device", "audit-test-" + UUID.randomUUID())
                .param("name", playerName)
                .query(UUID.class).single();

        db.sql("""
                        INSERT INTO leaderboard (player_id, mode, survived_seconds, kills,
                                                 reached_level, bosses_defeated)
                        VALUES (:player, 'infinite', 500, 90, 10, 1)""")
                .param("player", player).update();
    }

    /**
     * Read straight out of the table rather than through the repository, on purpose: a bug
     * that made the repository and the writer agree on the wrong column would be invisible to
     * a test that used the repository for both halves.
     */
    private List<Map<String, Object>> auditRows() {
        return db.sql("""
                        SELECT actor, action, target, summary, actor_ip
                        FROM admin_audit WHERE audit_id > :since ORDER BY audit_id""")
                .param("since", watermark)
                .query().listOfRows();
    }

    private Map<String, Object> onlyRow() {
        List<Map<String, Object>> rows = auditRows();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    // ── the destructive actions ────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleting a player is written down, and the entry outlives the player")
    void recordsAPlayerDeletion() throws Exception {
        mvc.perform(post("/admin/players/{id}/delete", player)
                        .param("confirmName", playerName)
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Map<String, Object> row = onlyRow();
        assertThat(row).containsEntry("actor", ADMIN)
                .containsEntry("action", "PLAYER_DELETED")
                .containsEntry("target", player.toString());

        // The whole reason actor and target are text and not foreign keys. The player row is
        // gone — with a cascade the log entry would have gone with it — and the entry still
        // names them, which is the only place that name now exists.
        Long survivors = db.sql("SELECT count(*) FROM player_profile WHERE player_id = :p")
                .param("p", player).query(Long.class).single();
        assertThat(survivors).isZero();
        assertThat((String) row.get("summary")).contains(playerName);
    }

    @Test
    @DisplayName("a delete refused for a mistyped name records nothing")
    void doesNotRecordARefusedDeletion() throws Exception {
        mvc.perform(post("/admin/players/{id}/delete", player)
                        .param("confirmName", playerName + "-oops")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Nothing was deleted, so there is nothing to account for. An entry here would put
        // "player deleted" next to a player who is still sitting in the table.
        assertThat(auditRows()).isEmpty();
    }

    @Test
    @DisplayName("removing a score is written down with the mode it was removed from")
    void recordsAScoreRemoval() throws Exception {
        mvc.perform(post("/admin/leaderboard/delete")
                        .param("playerId", player.toString())
                        .param("mode", "infinite")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Map<String, Object> row = onlyRow();
        assertThat(row).containsEntry("action", "SCORE_REMOVED")
                .containsEntry("target", player.toString());
        assertThat((String) row.get("summary")).contains("infinite");
    }

    @Test
    @DisplayName("removing a score that was already gone records nothing")
    void doesNotRecordANonRemoval() throws Exception {
        db.sql("DELETE FROM leaderboard WHERE player_id = :p").param("p", player).update();

        mvc.perform(post("/admin/leaderboard/delete")
                        .param("playerId", player.toString())
                        .param("mode", "infinite")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // The controller decides this from the number of rows the delete touched, not from
        // the request — which is why a request that looks identical produces no entry.
        assertThat(auditRows()).isEmpty();
    }

    @Test
    @DisplayName("a password change is written down; a refused one is not")
    void recordsOnlyARealPasswordChange() throws Exception {
        mvc.perform(post("/admin/password")
                        .param("currentPassword", "not-my-password")
                        .param("newPassword", "a-replacement-password")
                        .param("confirmPassword", "a-replacement-password")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()));

        assertThat(auditRows()).isEmpty();

        mvc.perform(post("/admin/password")
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", "a-replacement-password")
                        .param("confirmPassword", "a-replacement-password")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()));

        assertThat(onlyRow()).containsEntry("actor", ADMIN)
                .containsEntry("action", "PASSWORD_CHANGED");
    }

    // ── sign-in ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a successful sign-in is written down")
    void recordsASignIn() throws Exception {
        mvc.perform(formLogin("/admin/login").user(ADMIN).password(PASSWORD));

        assertThat(onlyRow()).containsEntry("actor", ADMIN)
                .containsEntry("action", "SIGNED_IN");
    }

    @Test
    @DisplayName("a refused sign-in is written down with the name that was tried")
    void recordsARefusedSignIn() throws Exception {
        mvc.perform(formLogin("/admin/login").user("root").password("hunter2"));

        // "root" is not an account and never will be. Storing it anyway is the point: the
        // value of this row is that somebody tried, and what they guessed.
        assertThat(onlyRow()).containsEntry("actor", "root")
                .containsEntry("action", "SIGN_IN_FAILED");
    }

    @Test
    @DisplayName("a sign-in with no username at all still produces a usable entry")
    void recordsAnEmptySignIn() throws Exception {
        // The parameter is whatever was posted, so it can be missing. A NOT NULL column and
        // an empty form are a 500 on the login page — reached by anyone, signed in or not.
        mvc.perform(post("/admin/login").with(csrf()));

        assertThat(onlyRow()).containsEntry("actor", "(unknown)")
                .containsEntry("action", "SIGN_IN_FAILED");
    }

    // ── the page ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the page shows what happened, including the name of a deleted player")
    void showsTheTrail() throws Exception {
        mvc.perform(post("/admin/players/{id}/delete", player)
                        .param("confirmName", playerName)
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()));

        mvc.perform(get("/admin/audit").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(playerName)))
                .andExpect(content().string(containsString("player deleted")));
    }

    @Test
    @DisplayName("the page is not readable without signing in")
    void refusesAnonymousReaders() throws Exception {
        // The trail names players, addresses and what was done to whom. It is the last page
        // in the backoffice that should be readable by a stranger.
        mvc.perform(get("/admin/audit"))
                .andExpect(status().is3xxRedirection());
    }

    // ── the guard that is not code ────────────────────────────────────────────────────

    @Test
    @DisplayName("there is no way to delete an audit entry")
    void exposesNoDelete() {
        // Append-only is enforced by absence: AdminAuditRepository extends the bare Repository
        // marker, which contributes nothing, so no delete exists to be called anywhere in the
        // application. This test exists because that guarantee is one convenient method
        // signature away from being lost, and nothing else would notice.
        List<String> methods = Arrays.stream(AdminAuditRepository.class.getMethods())
                .map(Method::getName)
                .toList();

        assertThat(methods).containsExactlyInAnyOrder("save", "count",
                "findAllByOrderByHappenedAtDescAuditIdDesc");
    }
}
