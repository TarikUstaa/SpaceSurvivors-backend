package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suspending a player, seen from both sides: the backoffice that does it, and the game endpoints
 * that must then refuse and hide the player.
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminSuspensionIntegrationTest {

    private static final String SECRET = "a-device-secret-long-enough-to-matter-32";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    @Autowired
    private ObjectMapper json;

    private record Device(String deviceId, String token, UUID playerId, String name) {
    }

    private Device device() throws Exception {
        String deviceId = "susp-" + UUID.randomUUID();
        String token = json.readTree(mvc.perform(post("/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"deviceSecret\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asString();
        var row = db.sql("SELECT player_id, display_name FROM player_profile WHERE device_id = :d")
                .param("d", deviceId).query().singleRow();
        return new Device(deviceId, token, (UUID) row.get("player_id"), (String) row.get("display_name"));
    }

    private void submit(Device d, double seconds, int expected) throws Exception {
        mvc.perform(post("/v1/leaderboard").header("Authorization", "Bearer " + d.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"infinite\",\"survivedSeconds\":" + seconds
                                 + ",\"kills\":10,\"reachedLevel\":5,\"bossesDefeated\":0}"))
                .andExpect(status().is(expected));
    }

    private void suspend(Device d, String reason) throws Exception {
        db.sql("INSERT INTO admin_user (username, password_hash, role) VALUES ('support-susp', 'x', 'SUPPORT') ON CONFLICT DO NOTHING").update();
        mvc.perform(post("/admin/players/" + d.playerId() + "/suspend").param("reason", reason)
                        .with(user("support-susp").roles("SUPPORT")).with(csrf()))
                .andExpect(flash().attributeExists("message"));
    }

    private int tokenStatus(Device d, String secret) throws Exception {
        return mvc.perform(post("/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + d.deviceId() + "\",\"deviceSecret\":\"" + secret + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("a suspended player's sign-in is refused with the reason, and their score leaves the board")
    void suspensionRefusesAndHides() throws Exception {
        Device cheat = device();
        Device honest = device();
        submit(cheat, 9000, 200);
        submit(honest, 100, 200);

        mvc.perform(get("/v1/leaderboard").param("mode", "infinite").param("limit", "200")
                        .header("Authorization", "Bearer " + honest.token()))
                .andExpect(jsonPath("$.entries[*].displayName", hasItem(cheat.name())));
        int honestRankBefore = json.readTree(mvc.perform(get("/v1/leaderboard").param("mode", "infinite")
                        .header("Authorization", "Bearer " + honest.token()))
                .andReturn().getResponse().getContentAsString()).get("me").get("rank").asInt();

        suspend(cheat, "impossible scores");

        mvc.perform(post("/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + cheat.deviceId() + "\",\"deviceSecret\":\"" + SECRET + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_suspended"))
                .andExpect(jsonPath("$.reason").value("impossible scores"));

        mvc.perform(get("/v1/leaderboard").param("mode", "infinite").param("limit", "200")
                        .header("Authorization", "Bearer " + honest.token()))
                .andExpect(jsonPath("$.entries[*].displayName", not(hasItem(cheat.name()))))
                // Everyone who ranked below the suspended player moves up one.
                .andExpect(jsonPath("$.me.rank").value(honestRankBefore - 1));

        // A token issued before the suspension cannot submit any more.
        submit(cheat, 9999, 403);
    }

    @Test
    @DisplayName("a wrong secret for a suspended account is a plain 401 — the suspension is not revealed")
    void wrongSecretDoesNotRevealSuspension() throws Exception {
        Device d = device();
        suspend(d, "spam");
        assertThat(tokenStatus(d, "not-the-secret-but-long-enough-000000")).isEqualTo(401);
    }

    @Test
    @DisplayName("lifting the suspension restores sign-in and the board")
    void lifting() throws Exception {
        Device d = device();
        suspend(d, "mistake");
        assertThat(tokenStatus(d, SECRET)).isEqualTo(403);

        mvc.perform(post("/admin/players/" + d.playerId() + "/unsuspend")
                        .with(user("support-susp").roles("SUPPORT")).with(csrf()))
                .andExpect(flash().attributeExists("message"));
        assertThat(tokenStatus(d, SECRET)).isEqualTo(200);

        assertThat(db.sql("SELECT count(*) FROM admin_audit WHERE action IN ('PLAYER_SUSPENDED','PLAYER_UNSUSPENDED') AND target = :t")
                .param("t", d.playerId().toString()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("no reason, no suspension; and suspending does not move last seen")
    void reasonRequiredAndLastSeenKept() throws Exception {
        Device d = device();
        db.sql("INSERT INTO admin_user (username, password_hash, role) VALUES ('support-susp', 'x', 'SUPPORT') ON CONFLICT DO NOTHING").update();

        mvc.perform(post("/admin/players/" + d.playerId() + "/suspend").param("reason", "   ")
                        .with(user("support-susp").roles("SUPPORT")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));
        assertThat(tokenStatus(d, SECRET)).isEqualTo(200);

        db.sql("UPDATE player_profile SET updated_at = now() - interval '30 days' WHERE player_id = :p")
                .param("p", d.playerId()).update();
        OffsetDateTime before = db.sql("SELECT updated_at FROM player_profile WHERE player_id = :p")
                .param("p", d.playerId()).query(OffsetDateTime.class).single();

        suspend(d, "testing");

        assertThat(db.sql("SELECT updated_at FROM player_profile WHERE player_id = :p")
                .param("p", d.playerId()).query(OffsetDateTime.class).single()).isEqualTo(before);
    }
}
