package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a SUPPORT account can and cannot do, and how fast a change to an account takes effect.
 *
 * <p>Every refusal below is paired with proof that the thing refused did not happen — a redirect
 * to a "not allowed" page is what the controller believes; the row still being there is what
 * actually matters.</p>
 *
 * <p>The SUPPORT account is a real {@code admin_user} row, not only a mocked principal. Since
 * {@link AdminSessionGuard} checks every session against the table, a principal with no row
 * behind it is signed out before any role rule runs — so a test that mocked the role without the
 * row would be testing the guard, not the role.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminRolesIntegrationTest {

    private static final String ADMIN = "testadmin";
    private static final String SUPPORT = "supportdesk";
    private static final String SUPPORT_PASSWORD = "support-desk-password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient db;

    @Autowired
    private AdminUserRepository admins;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private AdminPlayerService playerService;

    @Autowired
    private AdminUserService userService;

    private UUID player;
    private String playerName;
    private UUID supportId;

    @BeforeEach
    void seed() {
        supportId = admins.save(new AdminUser(SUPPORT, encoder.encode(SUPPORT_PASSWORD),
                AdminRole.SUPPORT)).getAdminId();

        playerName = "Roles" + (int) (Math.random() * 100_000);
        player = db.sql("""
                        INSERT INTO player_profile (device_id, display_name)
                        VALUES (:device, :name) RETURNING player_id""")
                .param("device", "roles-test-" + UUID.randomUUID())
                .param("name", playerName)
                .query(UUID.class).single();
        db.sql("""
                        INSERT INTO player_progress (player_id, progress_data)
                        VALUES (:p, cast('{"wallet":10}' as jsonb))""")
                .param("p", player).update();
    }

    private long playersNamed() {
        return db.sql("SELECT count(*) FROM player_profile WHERE player_id = :p")
                .param("p", player).query(Long.class).single();
    }

    // ── what SUPPORT does ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SUPPORT signs in for real and lands on the overview")
    void supportSignsIn() throws Exception {
        mvc.perform(formLogin("/admin/login").user(SUPPORT).password(SUPPORT_PASSWORD))
                .andExpect(authenticated().withUsername(SUPPORT).withRoles("SUPPORT"))
                .andExpect(redirectedUrl("/admin/overview"));
    }

    @Test
    @DisplayName("SUPPORT reads players, a player, the leaderboard and the save editor")
    void supportDoesTheDayToDayWork() throws Exception {
        mvc.perform(get("/admin/players").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/leaderboard").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/players/" + player + "/edit").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(status().isOk());

        mvc.perform(get("/admin/players/" + player).with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Edit save")))
                // Not offered — and refused anyway, which the next section proves.
                .andExpect(content().string(not(containsString("Delete player"))));
    }

    @Test
    @DisplayName("SUPPORT is not shown the ADMIN-only links; ADMIN is")
    void navFollowsTheRole() throws Exception {
        mvc.perform(get("/admin/players").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(content().string(not(containsString("/admin/users"))))
                .andExpect(content().string(not(containsString("/admin/audit"))));

        mvc.perform(get("/admin/players").with(user(ADMIN).roles("ADMIN")))
                .andExpect(content().string(containsString("/admin/users")))
                .andExpect(content().string(containsString("/admin/audit")));
    }

    // ── what SUPPORT does not ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("SUPPORT typing an ADMIN address gets the not-allowed page")
    void supportIsRefusedAdminPages() throws Exception {
        mvc.perform(get("/admin/users").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(redirectedUrl("/admin/forbidden"));
        mvc.perform(get("/admin/audit").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(redirectedUrl("/admin/forbidden"));

        // A page with a real 403, not a bare error dispatch — see StaleFormHandler.
        mvc.perform(get("/admin/forbidden").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Not allowed")));
    }

    @Test
    @DisplayName("SUPPORT cannot delete a player, even with the name typed correctly")
    void supportCannotDelete() throws Exception {
        mvc.perform(post("/admin/players/" + player + "/delete")
                        .param("confirmName", playerName)
                        .with(user(SUPPORT).roles("SUPPORT")).with(csrf()))
                .andExpect(redirectedUrl("/admin/forbidden"));

        assertThat(playersNamed()).isEqualTo(1);
    }

    @Test
    @DisplayName("SUPPORT cannot create an account for itself through the form")
    void supportCannotCreateAccounts() throws Exception {
        mvc.perform(post("/admin/users")
                        .param("username", "sneaky").param("role", "ADMIN")
                        .with(user(SUPPORT).roles("SUPPORT")).with(csrf()))
                .andExpect(redirectedUrl("/admin/forbidden"));

        assertThat(admins.findByUsernameIgnoreCase("sneaky")).isEmpty();
    }

    // ── the second check: @PreAuthorize on the operation itself ────────────────────────

    @Test
    @WithMockUser(username = SUPPORT, roles = "SUPPORT")
    @DisplayName("the delete operation refuses SUPPORT even when no URL rule is in the way")
    void deleteIsGuardedAtTheService() {
        // Called directly, with no HTTP request and so no URL rule. This is the case the
        // annotation exists for: a second screen, a job, anything that reaches the service by
        // another road.
        assertThatThrownBy(() -> playerService.delete(SUPPORT, player, playerName, null))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(playersNamed()).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = SUPPORT, roles = "SUPPORT")
    @DisplayName("SUPPORT cannot promote itself by calling the account service")
    void roleChangeIsGuardedAtTheService() {
        // The privilege escalation the whole role split would be worthless without stopping.
        assertThatThrownBy(() -> userService.changeRole(SUPPORT, supportId, "ADMIN", null))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(admins.findById(supportId).orElseThrow().getRole()).isEqualTo("SUPPORT");
    }

    // ── changes take effect on the next request, not the next sign-in ─────────────────

    @Test
    @DisplayName("a disabled account is signed out on its very next request")
    void disablingEndsTheSession() throws Exception {
        AdminUser account = admins.findById(supportId).orElseThrow();
        account.setEnabled(false);
        admins.saveAndFlush(account);

        mvc.perform(get("/admin/players").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(redirectedUrl("/admin/login?revoked"));
    }

    @Test
    @DisplayName("an account whose role changed is signed out rather than keeping the old one")
    void roleChangeEndsTheSession() throws Exception {
        // The dangerous direction: an ADMIN session for an account now demoted to SUPPORT.
        // Without the guard, this session would still open /admin/users until it expired.
        AdminUser account = admins.findById(supportId).orElseThrow();
        assertThat(account.getRole()).isEqualTo("SUPPORT");

        mvc.perform(get("/admin/users").with(user(SUPPORT).roles("ADMIN")))
                .andExpect(redirectedUrl("/admin/login?revoked"));
    }

    @Test
    @DisplayName("an account with a temporary password can open nothing but the password form")
    void temporaryPasswordMustBeReplacedFirst() throws Exception {
        AdminUser account = admins.findById(supportId).orElseThrow();
        account.setMustChangePassword(true);
        admins.saveAndFlush(account);

        mvc.perform(get("/admin/players").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(redirectedUrl("/admin/password?required"));
        mvc.perform(get("/admin/players/" + player + "/edit").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(redirectedUrl("/admin/password?required"));

        mvc.perform(get("/admin/password").with(user(SUPPORT).roles("SUPPORT")))
                .andExpect(status().isOk());
    }
}
