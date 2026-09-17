package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating and managing backoffice accounts, followed all the way to the person signing in.
 *
 * <p>The first test is the one the feature is for: an administrator creates an account, the
 * temporary password it was given signs in, and the account can do nothing until its owner
 * replaces that password — after which it can. Each step is only worth something if the next one
 * works, so they are one test rather than four.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminUsersIntegrationTest {

    private static final String ADMIN = "testadmin";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AdminUserRepository admins;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private JdbcClient db;

    private MvcResult create(String username, String role) throws Exception {
        return mvc.perform(post("/admin/users")
                        .param("username", username).param("role", role)
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"))
                .andReturn();
    }

    @SuppressWarnings("unchecked")
    private static String issuedPassword(MvcResult result) {
        Map<String, String> issued = (Map<String, String>) result.getFlashMap().get("issued");
        assertThat(issued).as("a temporary password was handed back").isNotNull();
        return issued.get("password");
    }

    private long auditRows(String action, String target) {
        return db.sql("SELECT count(*) FROM admin_audit WHERE action = :a AND target = :t")
                .param("a", action).param("t", target).query(Long.class).single();
    }

    // ── the whole journey ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a created account signs in with its temporary password and must replace it first")
    void createSignInReplace() throws Exception {
        String temporary = issuedPassword(create("newsupport", "SUPPORT"));

        AdminUser created = admins.findByUsernameIgnoreCase("newsupport").orElseThrow();
        assertThat(created.getRole()).isEqualTo("SUPPORT");
        assertThat(created.isMustChangePassword()).isTrue();
        // The hash, never the password — and the password it hashes is the one handed back.
        assertThat(created.getPasswordHash()).isNotEqualTo(temporary);
        assertThat(encoder.matches(temporary, created.getPasswordHash())).isTrue();
        assertThat(auditRows("ACCOUNT_CREATED", "newsupport")).isEqualTo(1);

        // Signs in...
        MockHttpSession session = (MockHttpSession) mvc
                .perform(formLogin("/admin/login").user("newsupport").password(temporary))
                .andExpect(authenticated().withRoles("SUPPORT"))
                .andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();

        // ...but cannot open anything yet.
        mvc.perform(get("/admin/players").session(session))
                .andExpect(redirectedUrl("/admin/password?required"));

        // Replaces the password, using the temporary one as the current password.
        mvc.perform(post("/admin/password").session(session).with(csrf())
                        .param("currentPassword", temporary)
                        .param("newPassword", "chosen-by-its-owner")
                        .param("confirmPassword", "chosen-by-its-owner"))
                .andExpect(flash().attributeExists("message"));

        // And now it can.
        mvc.perform(get("/admin/players").session(session))
                .andExpect(status().isOk());
        assertThat(admins.findByUsernameIgnoreCase("newsupport").orElseThrow()
                .isMustChangePassword()).isFalse();
    }

    // ── refusals that change nothing ───────────────────────────────────────────────────

    @Test
    @DisplayName("a name that differs only in case is refused, and no second row appears")
    void refusesADuplicateName() throws Exception {
        create("Duplicate", "SUPPORT");

        MvcResult second = create("duplicate", "ADMIN");
        assertThat(second.getFlashMap().get("warning")).isNotNull();
        assertThat(second.getFlashMap().get("issued")).isNull();

        long rows = db.sql("SELECT count(*) FROM admin_user WHERE lower(username) = 'duplicate'")
                .query(Long.class).single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed username or an unknown role creates nothing")
    void refusesBadInput() throws Exception {
        create("x", "SUPPORT");
        create("has space", "SUPPORT");
        create("validname", "SUPERUSER");

        assertThat(admins.findByUsernameIgnoreCase("x")).isEmpty();
        assertThat(admins.findByUsernameIgnoreCase("has space")).isEmpty();
        assertThat(admins.findByUsernameIgnoreCase("validname")).isEmpty();
    }

    @Test
    @DisplayName("an administrator cannot disable, demote or reset their own account here")
    void cannotActOnYourself() throws Exception {
        UUID me = admins.findByUsernameIgnoreCase(ADMIN).orElseThrow().getAdminId();
        String hashBefore = admins.findById(me).orElseThrow().getPasswordHash();

        mvc.perform(post("/admin/users/" + me + "/enabled").param("enabled", "false")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));
        mvc.perform(post("/admin/users/" + me + "/role").param("role", "SUPPORT")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));
        mvc.perform(post("/admin/users/" + me + "/password")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));

        AdminUser after = admins.findById(me).orElseThrow();
        assertThat(after.isEnabled()).isTrue();
        assertThat(after.getRole()).isEqualTo("ADMIN");
        assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("an account form without a CSRF token creates nothing")
    void refusesWithoutCsrf() throws Exception {
        mvc.perform(post("/admin/users").param("username", "nocsrf").param("role", "SUPPORT")
                        .with(user(ADMIN).roles("ADMIN")))
                .andExpect(redirectedUrl("/admin/login?expired"));

        assertThat(admins.findByUsernameIgnoreCase("nocsrf")).isEmpty();
    }

    // ── managing somebody else ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("disabling another account is recorded and refuses its sign-in")
    void disablesAnotherAccount() throws Exception {
        String temporary = issuedPassword(create("leaving", "SUPPORT"));
        UUID id = admins.findByUsernameIgnoreCase("leaving").orElseThrow().getAdminId();

        mvc.perform(post("/admin/users/" + id + "/enabled").param("enabled", "false")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("message"));

        assertThat(admins.findById(id).orElseThrow().isEnabled()).isFalse();
        assertThat(auditRows("ACCOUNT_DISABLED", "leaving")).isEqualTo(1);

        mvc.perform(formLogin("/admin/login").user("leaving").password(temporary))
                .andExpect(unauthenticated());
    }

    @Test
    @DisplayName("changing another account's role is recorded; the same role twice is not")
    void changesAnotherRole() throws Exception {
        create("promoted", "SUPPORT");
        UUID id = admins.findByUsernameIgnoreCase("promoted").orElseThrow().getAdminId();

        mvc.perform(post("/admin/users/" + id + "/role").param("role", "admin")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("message"));
        mvc.perform(post("/admin/users/" + id + "/role").param("role", "ADMIN")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));

        assertThat(admins.findById(id).orElseThrow().getRole()).isEqualTo("ADMIN");
        assertThat(auditRows("ACCOUNT_ROLE_CHANGED", "promoted")).isEqualTo(1);
    }

    @Test
    @DisplayName("a reset replaces the old password with a temporary one that must be changed")
    void resetsAnotherPassword() throws Exception {
        String first = issuedPassword(create("forgetful", "SUPPORT"));
        UUID id = admins.findByUsernameIgnoreCase("forgetful").orElseThrow().getAdminId();

        MvcResult reset = mvc.perform(post("/admin/users/" + id + "/password")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andReturn();
        String second = issuedPassword(reset);

        assertThat(second).isNotEqualTo(first);
        mvc.perform(formLogin("/admin/login").user("forgetful").password(first))
                .andExpect(unauthenticated());
        mvc.perform(formLogin("/admin/login").user("forgetful").password(second))
                .andExpect(authenticated());
        assertThat(admins.findById(id).orElseThrow().isMustChangePassword()).isTrue();
        assertThat(auditRows("ACCOUNT_PASSWORD_RESET", "forgetful")).isEqualTo(1);
    }

    @Test
    @DisplayName("the database refuses a role the application does not know")
    void databaseConstrainsTheRole() {
        // The CHECK in V5, tested without the Java in the way — it is what protects the column
        // from a hand-typed statement, which no enum can.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("""
                        INSERT INTO admin_user (username, password_hash, role)
                        VALUES ('typo', 'x', 'ADMN')""").update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
