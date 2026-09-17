package com.tarikusta.spacesurvivors.game;

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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Remote game settings: bounded overrides set in the backoffice, read by the game without a token. */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class GameConfigIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    @Autowired
    private GameContentService content;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM app_setting WHERE key = 'game_config'").update();
    }

    private MockHttpServletRequestBuilder save(String... pairs) {
        MockHttpServletRequestBuilder request = post("/admin/game/config");
        for (int i = 0; i < pairs.length; i += 2) {
            request.param(pairs[i], pairs[i + 1]);
        }
        return request.with(user("testadmin").roles("ADMIN")).with(csrf());
    }

    @Test
    @DisplayName("no overrides: an empty object, no token needed")
    void emptyByDefault() throws Exception {
        mvc.perform(get("/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides").isEmpty());
    }

    @Test
    @DisplayName("overrides are saved, served, and blanks mean the game's own value")
    void saveAndServe() throws Exception {
        mvc.perform(save("curseChance", "0.5", "curseMinLevel", "3", "enemyHealthScale", "1,5", "eliteChance", ""))
                .andExpect(redirectedUrl("/admin/game"))
                .andExpect(flash().attributeExists("message"));

        mvc.perform(get("/v1/config"))
                .andExpect(jsonPath("$.overrides.curseChance").value(0.5))
                .andExpect(jsonPath("$.overrides.curseMinLevel").value(3))
                .andExpect(jsonPath("$.overrides.enemyHealthScale").value(1.5))
                .andExpect(jsonPath("$.overrides.eliteChance").doesNotExist());

        String summary = db.sql("SELECT summary FROM admin_audit WHERE action = 'GAME_CONFIG_CHANGED'")
                .query(String.class).single();
        assertThat(summary).contains("curseChance default → 0.5").contains("enemyHealthScale default → 1.5");
    }

    @Test
    @DisplayName("clearing every field removes the overrides and records what went back to default")
    void clearingGoesBackToDefaults() throws Exception {
        mvc.perform(save("xpGainScale", "2"));
        mvc.perform(save("xpGainScale", "")).andExpect(flash().attributeExists("message"));

        mvc.perform(get("/v1/config")).andExpect(jsonPath("$.overrides").isEmpty());
        assertThat(db.sql("SELECT count(*) FROM app_setting WHERE key = 'game_config'").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("one value out of range, not whole, or not a number saves nothing at all")
    void allOrNothing() throws Exception {
        mvc.perform(save("xpGainScale", "2"));

        for (String[] bad : new String[][]{
                {"enemyHealthScale", "50"}, {"curseChance", "-0.1"}, {"curseMinLevel", "2.5"}, {"spawnRateScale", "fast"}}) {
            // A valid change beside the bad one, in a different field, which must not land either.
            mvc.perform(save("eliteChance", "0.9", "xpGainScale", "2", bad[0], bad[1]))
                    .andExpect(flash().attributeExists("warning"));
        }

        assertThat(content.gameConfig()).containsOnlyKeys("xpGainScale");
    }

    @Test
    @DisplayName("an unchanged form saves nothing and writes no audit row")
    void unchanged() throws Exception {
        mvc.perform(save("xpGainScale", "2"));
        mvc.perform(save("xpGainScale", "2.0")).andExpect(flash().attributeExists("warning"));
        assertThat(db.sql("SELECT count(*) FROM admin_audit WHERE action = 'GAME_CONFIG_CHANGED'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("a row edited by hand is filtered on the way out: unknown keys and out-of-range values dropped")
    void readIsDefensive() {
        db.sql("""
                INSERT INTO app_setting (key, value, updated_by)
                VALUES ('game_config', '{"curseChance": 0.3, "godMode": 1, "enemyHealthScale": 99, "xpGainScale": "2"}', 'psql')""")
                .update();

        assertThat(content.gameConfig()).containsOnlyKeys("curseChance");
        assertThat(content.gameConfig().get("curseChance")).isEqualByComparingTo(new BigDecimal("0.3"));
    }

    @Test
    @DisplayName("SUPPORT cannot change game settings")
    void supportCannot() throws Exception {
        db.sql("INSERT INTO admin_user (username, password_hash, role) VALUES ('support-gc', 'x', 'SUPPORT')").update();
        mvc.perform(post("/admin/game/config").param("xpGainScale", "3")
                        .with(user("support-gc").roles("SUPPORT")).with(csrf()))
                .andExpect(redirectedUrl("/admin/forbidden"));
        assertThat(content.gameConfig()).isEmpty();
    }
}
