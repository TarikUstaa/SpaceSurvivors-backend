package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Editing a save from the backoffice.
 *
 * <p>Three things are being proven, and the third is the one the feature exists for:</p>
 * <ol>
 *   <li>the fields that were edited change, and <b>nothing else in the save does</b> — including
 *       properties this server has never heard of;</li>
 *   <li>a bad or stale form writes nothing at all;</li>
 *   <li>the edit reaches the game in the shape the game needs: a stale PUT from the device gets a
 *       409 whose body carries the edited copy <em>and</em> a higher {@code adminRevision}, which is
 *       the signal that makes {@code ProfileMerge} take the copy whole instead of merging it away.</li>
 * </ol>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminProgressEditIntegrationTest {

    private static final String ADMIN = "testadmin";

    private static final String SAVE = """
            {"userId":"placeholder","schemaVersion":5,
             "wallet":100,"lifetimeScrap":500,"lifetimeKills":40,"runsPlayed":4,
             "bestKills":83,"bestSurvivalSeconds":120,"bestLevel":9,"bossKills":1,
             "unlockedAchievementIds":["first_blood","survivor"],
             "ownedShipIds":["starter"],"selectedShipId":"starter",
             "metaUpgradeLevels":{"damage":2},
             "selectedMapId":"milky_way",
             "someFutureField":{"kept":true}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    @Autowired
    private ObjectMapper json;

    private UUID player;

    @BeforeEach
    void seed() {
        player = db.sql("""
                        INSERT INTO player_profile (device_id, display_name)
                        VALUES (:device, :name) RETURNING player_id""")
                .param("device", "edit-test-" + UUID.randomUUID())
                .param("name", "Edit" + (int) (Math.random() * 100_000))
                .query(UUID.class).single();
        db.sql("INSERT INTO player_progress (player_id, progress_data) VALUES (:p, cast(:s as jsonb))")
                .param("p", player).param("s", SAVE).update();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private int version() {
        return db.sql("SELECT version FROM player_progress WHERE player_id = :p")
                .param("p", player).query(Integer.class).single();
    }

    private JsonNode stored() {
        return json.readTree(db.sql("SELECT progress_data::text FROM player_progress WHERE player_id = :p")
                .param("p", player).query(String.class).single());
    }

    private long audited() {
        return db.sql("SELECT count(*) FROM admin_audit WHERE action = 'PROGRESS_EDITED' AND target = :t")
                .param("t", player.toString()).query(Long.class).single();
    }

    /** The form exactly as the page draws it, so each test changes only what it is about. */
    private Map<String, String> unchangedForm() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("version", String.valueOf(version()));
        form.put("wallet", "100");
        form.put("lifetimeScrap", "500");
        form.put("lifetimeKills", "40");
        form.put("runsPlayed", "4");
        form.put("bestKills", "83");
        form.put("bestSurvivalSeconds", "120");
        form.put("bestLevel", "9");
        form.put("bossKills", "1");
        form.put("unlockedAchievementIds", "first_blood\nsurvivor");
        form.put("ownedShipIds", "starter");
        form.put("selectedShipId", "starter");
        form.put("metaUpgradeLevels", "damage=2");
        return form;
    }

    private MockHttpServletRequestBuilder submit(Map<String, String> form, String role) {
        MockHttpServletRequestBuilder request = post("/admin/players/" + player + "/edit");
        form.forEach(request::param);
        return request.with(user(role.equals("ADMIN") ? ADMIN : "support-edit").roles(role)).with(csrf());
    }

    // ── reading ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the form is filled from the save")
    void formShowsTheSave() throws Exception {
        mvc.perform(get("/admin/players/" + player + "/edit").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"83\"")))
                .andExpect(content().string(containsString("damage=2")))
                .andExpect(content().string(containsString("survivor")));
    }

    @Test
    @DisplayName("a player with no save is sent back to their page, not shown an empty form")
    void noSaveNoForm() throws Exception {
        db.sql("DELETE FROM player_progress WHERE player_id = :p").param("p", player).update();

        mvc.perform(get("/admin/players/" + player + "/edit").with(user(ADMIN).roles("ADMIN")))
                .andExpect(redirectedUrl("/admin/players/" + player))
                .andExpect(flash().attributeExists("warning"));
    }

    // ── editing ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an edit changes the named fields in both directions and leaves the rest alone")
    void editsWhatWasAskedAndNothingElse() throws Exception {
        int before = version();

        Map<String, String> form = unchangedForm();
        form.put("wallet", "50000");                      // up
        form.put("bestKills", "0");                       // down — a merge alone would undo this
        form.put("unlockedAchievementIds", "survivor");   // one removed
        form.put("ownedShipIds", "starter\nvanguard");    // one added
        form.put("metaUpgradeLevels", "damage=5\nhealth=1");

        mvc.perform(submit(form, "ADMIN"))
                .andExpect(redirectedUrl("/admin/players/" + player))
                .andExpect(flash().attributeExists("message"));

        JsonNode save = stored();
        assertThat(save.path("wallet").asLong()).isEqualTo(50000);
        assertThat(save.path("bestKills").asInt()).isZero();
        assertThat(save.path("unlockedAchievementIds").toString()).isEqualTo("[\"survivor\"]");
        assertThat(save.path("ownedShipIds").toString()).isEqualTo("[\"starter\",\"vanguard\"]");
        assertThat(save.path("metaUpgradeLevels").path("damage").asInt()).isEqualTo(5);
        assertThat(save.path("metaUpgradeLevels").path("health").asInt()).isEqualTo(1);

        // Untouched: fields the form does not show, and one this server has never heard of.
        assertThat(save.path("lifetimeScrap").asLong()).isEqualTo(500);
        assertThat(save.path("selectedMapId").asString()).isEqualTo("milky_way");
        assertThat(save.path("userId").asString()).isEqualTo("placeholder");
        assertThat(save.path("someFutureField").path("kept").asBoolean()).isTrue();

        // The two numbers that make the game notice and obey.
        assertThat(save.path("adminRevision").asLong()).isEqualTo(1);
        assertThat(version()).isEqualTo(before + 1);

        // Old values survive only in the audit row, so they must be there.
        String summary = db.sql("SELECT summary FROM admin_audit WHERE action = 'PROGRESS_EDITED' AND target = :t")
                .param("t", player.toString()).query(String.class).single();
        assertThat(summary).contains("wallet 100 → 50000").contains("bestKills 83 → 0");
    }

    @Test
    @DisplayName("every edit raises the revision again")
    void revisionClimbs() throws Exception {
        Map<String, String> form = unchangedForm();
        form.put("wallet", "1");
        mvc.perform(submit(form, "ADMIN"));

        form = unchangedForm();
        form.put("wallet", "2");
        mvc.perform(submit(form, "ADMIN"));

        assertThat(stored().path("adminRevision").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("SUPPORT may edit a save")
    void supportEdits() throws Exception {
        // Seeded as a real account: AdminSessionGuard signs out a session with no row behind it.
        db.sql("INSERT INTO admin_user (username, password_hash, role) VALUES ('support-edit', 'x', 'SUPPORT')")
                .update();

        Map<String, String> form = unchangedForm();
        form.put("wallet", "777");
        mvc.perform(submit(form, "SUPPORT"))
                .andExpect(flash().attributeExists("message"));

        assertThat(stored().path("wallet").asLong()).isEqualTo(777);
    }

    @Test
    @DisplayName("the starter can stay selected even though it is never in the owned list")
    void theStarterIsOwnedWithoutBeingListed() throws Exception {
        // The shape a real save has: the game counts the starter as owned implicitly and never
        // writes it into ownedShipIds. This form used to refuse such a save outright — every edit
        // failed until the operator cleared the selected ship.
        db.sql("""
                        UPDATE player_progress
                        SET progress_data = jsonb_set(progress_data, '{ownedShipIds}', '["vanguard"]')
                        WHERE player_id = :p""")
                .param("p", player).update();

        Map<String, String> form = unchangedForm();
        form.put("ownedShipIds", "vanguard");
        form.put("wallet", "4242");
        mvc.perform(submit(form, "ADMIN"))
                .andExpect(redirectedUrl("/admin/players/" + player))
                .andExpect(flash().attributeExists("message"));

        JsonNode save = stored();
        assertThat(save.path("wallet").asLong()).isEqualTo(4242);
        assertThat(save.path("selectedShipId").asString()).isEqualTo("starter");

        // And blank, which the game reads as the starter too.
        form = unchangedForm();
        form.put("ownedShipIds", "vanguard");
        form.put("wallet", "4242");
        form.put("selectedShipId", "");
        mvc.perform(submit(form, "ADMIN"))
                .andExpect(flash().attributeExists("message"));
        assertThat(stored().path("selectedShipId").asString()).isEmpty();
    }

    // ── refusals that write nothing ────────────────────────────────────────────────────

    @Test
    @DisplayName("submitting the form unchanged writes nothing and does not raise the revision")
    void unchangedWritesNothing() throws Exception {
        // Raising the revision here would make the device discard local progress for no reason.
        int before = version();
        mvc.perform(submit(unchangedForm(), "ADMIN"))
                .andExpect(flash().attributeExists("warning"));

        assertThat(version()).isEqualTo(before);
        assertThat(stored().has("adminRevision")).isFalse();
        assertThat(audited()).isZero();
    }

    @Test
    @DisplayName("a form drawn before the game saved again is refused")
    void staleFormIsRefused() throws Exception {
        Map<String, String> form = unchangedForm();
        form.put("wallet", "999");
        form.put("version", String.valueOf(version() - 1));

        mvc.perform(submit(form, "ADMIN"))
                .andExpect(redirectedUrl("/admin/players/" + player + "/edit"));

        assertThat(stored().path("wallet").asLong()).isEqualTo(100);
        assertThat(audited()).isZero();
    }

    @Test
    @DisplayName("one bad field refuses the whole form — the good fields above it are not written")
    void invalidFieldsWriteNothing() throws Exception {
        String[][] bad = {
                {"bestKills", "-5"},
                {"bestKills", "twelve"},
                {"runsPlayed", "3000000000"},         // past what the client's int can hold
                {"unlockedAchievementIds", "First Blood"},
                {"metaUpgradeLevels", "damage"},
                {"selectedShipId", "not_owned"},
        };
        for (String[] field : bad) {
            Map<String, String> form = unchangedForm();
            form.put("wallet", "12345");              // a valid change that must not land
            form.put(field[0], field[1]);

            mvc.perform(submit(form, "ADMIN"))
                    .andExpect(redirectedUrl("/admin/players/" + player + "/edit"));

            assertThat(stored().path("wallet").asLong())
                    .as("wallet after a bad %s of '%s'", field[0], field[1])
                    .isEqualTo(100);
        }
        assertThat(audited()).isZero();
    }

    // ── the contract with the game ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the device's next save meets a 409 carrying the edit and the higher revision")
    void theGameIsToldAboutTheEdit() throws Exception {
        // A real device: authenticate, then save a first copy through the game's own endpoint.
        String device = "edit-contract-" + UUID.randomUUID();
        String token = json.readTree(mvc.perform(post("/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"%s","deviceSecret":"a-device-secret-long-enough-to-matter-32"}"""
                                .formatted(device)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asString();

        // Read back rather than assumed: a row created through JPA starts at @Version's own
        // initial value, which is not the column's SQL default.
        int deviceVersion = json.readTree(mvc.perform(put("/v1/progress")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"progress":{"wallet":10,"lifetimeScrap":10},"version":0}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("version").asInt();

        UUID devicePlayer = db.sql("SELECT player_id FROM player_profile WHERE device_id = :d")
                .param("d", device).query(UUID.class).single();

        // The operator edits it.
        MockHttpServletRequestBuilder edit = post("/admin/players/" + devicePlayer + "/edit")
                .param("version", String.valueOf(deviceVersion))
                .param("wallet", "999").param("lifetimeScrap", "10")
                .param("lifetimeKills", "0").param("runsPlayed", "0").param("bestKills", "0")
                .param("bestSurvivalSeconds", "0").param("bestLevel", "0").param("bossKills", "0")
                .param("unlockedAchievementIds", "").param("ownedShipIds", "")
                .param("selectedShipId", "").param("metaUpgradeLevels", "")
                .with(user(ADMIN).roles("ADMIN")).with(csrf());
        mvc.perform(edit).andExpect(flash().attributeExists("message"));

        // The device, unaware, saves its old copy with the version it last saw.
        mvc.perform(put("/v1/progress").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"progress":{"wallet":10,"lifetimeScrap":10},"version":%d}"""
                                .formatted(deviceVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.progress.wallet").value(999))
                .andExpect(jsonPath("$.progress.adminRevision").value(1));
    }
}
