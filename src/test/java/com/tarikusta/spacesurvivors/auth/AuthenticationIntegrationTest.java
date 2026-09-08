package com.tarikusta.spacesurvivors.auth;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole authentication path, against the real filter chain and a real database.
 *
 * <p>This is the test that matters most in the suite. Every other one assumes identity has
 * already been established; this is the only place that proves it actually is — that an
 * unauthenticated call is refused, that a token is granted only for a matching secret, and
 * that a token cannot simply be written by hand.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AuthenticationIntegrationTest {

    private static final String SECRET = "a-device-secret-long-enough-to-matter-32";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient db;

    private static String device() {
        return "test-" + UUID.randomUUID();
    }

    private String tokenBody(String deviceId, String secret) {
        return """
                {"deviceId":"%s","deviceSecret":"%s"}""".formatted(deviceId, secret);
    }

    private String obtainToken(String deviceId) throws Exception {
        String response = mvc.perform(post("/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(deviceId, SECRET)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("token").asString();
    }

    @Test
    @DisplayName("without a token there is no access at all")
    void refusesAnUnauthenticatedRequest() throws Exception {
        mvc.perform(get("/v1/player")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/progress")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/leaderboard?mode=infinite")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a new device is registered and handed a token")
    void registersOnFirstAuthentication() throws Exception {
        String deviceId = device();

        mvc.perform(post("/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(deviceId, SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(3600));

        // The secret must never be recoverable from the database.
        String stored = db.sql("SELECT device_secret_hash FROM player_profile WHERE device_id = :d")
                .param("d", deviceId).query(String.class).single();
        assertThat(stored).isNotEqualTo(SECRET).startsWith("$2");
    }

    @Test
    @DisplayName("the token opens the endpoints it should")
    void aTokenGrantsAccess() throws Exception {
        String token = obtainToken(device());

        mvc.perform(get("/v1/player").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").isNotEmpty());
    }

    @Test
    @DisplayName("the same device with the same secret comes back as the same player")
    void authenticatingTwiceKeepsOneIdentity() throws Exception {
        String deviceId = device();

        String firstName = mvc.perform(get("/v1/player")
                        .header("Authorization", "Bearer " + obtainToken(deviceId)))
                .andReturn().getResponse().getContentAsString();
        String secondName = mvc.perform(get("/v1/player")
                        .header("Authorization", "Bearer " + obtainToken(deviceId)))
                .andReturn().getResponse().getContentAsString();

        assertThat(firstName).isEqualTo(secondName);
        Integer rows = db.sql("SELECT count(*) FROM player_profile WHERE device_id = :d")
                .param("d", deviceId).query(Integer.class).single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("the wrong secret gets nothing, and says nothing about why")
    void refusesAWrongSecret() throws Exception {
        String deviceId = device();
        obtainToken(deviceId);

        String body = mvc.perform(post("/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(deviceId, "a-completely-different-secret-here-32")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(deviceId);
    }

    @Test
    @DisplayName("a short secret is refused before it is ever hashed")
    void rejectsASecretTooShortToBeWorthHashing() throws Exception {
        mvc.perform(post("/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(device(), "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.deviceSecret").exists());
    }

    @Test
    @DisplayName("a token cannot be forged, which is the entire point")
    void refusesATamperedToken() throws Exception {
        String token = obtainToken(device());

        // Flip the last character of the signature. Everything else about the token is
        // still valid — this is exactly what the old device header could not detect.
        char last = token.charAt(token.length() - 1);
        String forged = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        mvc.perform(get("/v1/player").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("nonsense in the header is refused, not mistaken for a token")
    void refusesGarbage() throws Exception {
        mvc.perform(get("/v1/player").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/player").header("Authorization", "Device some-device-id"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("public paths still answer without a token")
    void leavesPublicPathsOpen() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }
}
