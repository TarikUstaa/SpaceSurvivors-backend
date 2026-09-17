package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two-factor sign-in, followed through real sign-ins.
 *
 * <p>The codes are computed here the way the phone would compute them — from the secret, the time,
 * and {@link Totp} — so these tests exercise the whole exchange rather than a mocked verifier.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminTwoFactorIntegrationTest {

    private static final String NAME = "twofa-user";
    private static final String PASSWORD = "twofa-password-long";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AdminUserRepository admins;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private JdbcClient db;

    @BeforeEach
    void seed() {
        admins.save(new AdminUser(NAME, encoder.encode(PASSWORD), AdminRole.ADMIN));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private MockHttpSession signIn() throws Exception {
        return (MockHttpSession) mvc.perform(formLogin("/admin/login").user(NAME).password(PASSWORD))
                .andExpect(authenticated())
                .andReturn().getRequest().getSession(false);
    }

    private static String codeNow(String secret, int stepOffset) {
        long step = Instant.now().getEpochSecond() / Totp.STEP_SECONDS + stepOffset;
        return Totp.codeFor(Totp.unbase32(secret), step);
    }

    private String storedSecret() {
        return admins.findByUsernameIgnoreCase(NAME).orElseThrow().getTotpSecret();
    }

    /** Turn two-factor on through the pages and return the recovery codes that were shown. */
    @SuppressWarnings("unchecked")
    private List<String> enable(MockHttpSession session) throws Exception {
        mvc.perform(post("/admin/security/two-factor/begin").session(session).with(csrf()))
                .andExpect(redirectedUrl("/admin/security"));
        var enrollment = (AdminTwoFactorService.Enrollment)
                session.getAttribute(AdminTwoFactorController.ENROLLING);
        assertThat(enrollment).isNotNull();

        MvcResult result = mvc.perform(post("/admin/security/two-factor/enable").session(session).with(csrf())
                        .param("currentPassword", PASSWORD)
                        .param("code", codeNow(enrollment.secret(), 0)))
                .andExpect(flash().attributeExists("message"))
                .andReturn();
        return (List<String>) result.getFlashMap().get("recoveryCodes");
    }

    /**
     * Codes are single-use per 30-second step, and enabling already used this step's code — so a
     * test that signs in again straight away clears the replay guard first. Through JPA, not SQL:
     * the test's transaction holds the account in its persistence context, and an UPDATE behind
     * its back would leave the service reading the old value.
     */
    private void forgetLastStep() {
        AdminUser account = admins.findByUsernameIgnoreCase(NAME).orElseThrow();
        account.setTotpLastStep(null);
        admins.saveAndFlush(account);
    }

    // ── enrolment ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("turning it on needs the password and a correct code; nothing is saved before that")
    void enrolment() throws Exception {
        MockHttpSession session = signIn();
        mvc.perform(post("/admin/security/two-factor/begin").session(session).with(csrf()));
        var enrollment = (AdminTwoFactorService.Enrollment)
                session.getAttribute(AdminTwoFactorController.ENROLLING);

        // Begun, not saved.
        assertThat(storedSecret()).isNull();

        mvc.perform(post("/admin/security/two-factor/enable").session(session).with(csrf())
                        .param("currentPassword", "wrong").param("code", codeNow(enrollment.secret(), 0)))
                .andExpect(flash().attributeExists("warning"));
        mvc.perform(post("/admin/security/two-factor/enable").session(session).with(csrf())
                        .param("currentPassword", PASSWORD).param("code", "000000"))
                .andExpect(flash().attributeExists("warning"));
        assertThat(storedSecret()).isNull();

        MvcResult ok = mvc.perform(post("/admin/security/two-factor/enable").session(session).with(csrf())
                        .param("currentPassword", PASSWORD).param("code", codeNow(enrollment.secret(), 0)))
                .andExpect(flash().attributeExists("recoveryCodes"))
                .andReturn();

        assertThat(storedSecret()).isEqualTo(enrollment.secret());
        assertThat((List<?>) ok.getFlashMap().get("recoveryCodes")).hasSize(8);
        assertThat(db.sql("SELECT count(*) FROM admin_recovery_code rc JOIN admin_user a USING (admin_id) WHERE a.username = :n")
                .param("n", NAME).query(Long.class).single()).isEqualTo(8);
    }

    // ── signing in ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("with two-factor on, the password alone opens nothing but the code form")
    void passwordAloneIsNotEnough() throws Exception {
        enable(signIn());
        forgetLastStep();

        MockHttpSession session = (MockHttpSession) mvc
                .perform(formLogin("/admin/login").user(NAME).password(PASSWORD))
                .andExpect(redirectedUrl("/admin/2fa"))
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/admin/players").session(session)).andExpect(redirectedUrl("/admin/2fa"));
        mvc.perform(get("/admin/users").session(session)).andExpect(redirectedUrl("/admin/2fa"));
        mvc.perform(get("/admin/2fa").session(session)).andExpect(status().isOk());

        mvc.perform(post("/admin/2fa").session(session).with(csrf()).param("code", "123456"))
                .andExpect(redirectedUrl("/admin/2fa?error"));
        mvc.perform(get("/admin/players").session(session)).andExpect(redirectedUrl("/admin/2fa"));
        assertThat(db.sql("SELECT count(*) FROM admin_audit WHERE action = 'TWO_FACTOR_FAILED' AND actor = :n")
                .param("n", NAME).query(Long.class).single()).isEqualTo(1);

        mvc.perform(post("/admin/2fa").session(session).with(csrf()).param("code", codeNow(storedSecret(), 0)))
                .andExpect(redirectedUrl("/admin/overview"));
        mvc.perform(get("/admin/players").session(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a code that was already accepted is refused the second time")
    void noReplay() throws Exception {
        enable(signIn());
        forgetLastStep();
        String code = codeNow(storedSecret(), 0);

        MockHttpSession first = (MockHttpSession) mvc.perform(formLogin("/admin/login").user(NAME).password(PASSWORD))
                .andReturn().getRequest().getSession(false);
        mvc.perform(post("/admin/2fa").session(first).with(csrf()).param("code", code))
                .andExpect(redirectedUrl("/admin/overview"));

        MockHttpSession second = (MockHttpSession) mvc.perform(formLogin("/admin/login").user(NAME).password(PASSWORD))
                .andReturn().getRequest().getSession(false);
        mvc.perform(post("/admin/2fa").session(second).with(csrf()).param("code", code))
                .andExpect(redirectedUrl("/admin/2fa?error"));
    }

    @Test
    @DisplayName("a recovery code signs in once, in any spelling, and never again")
    void recoveryCode() throws Exception {
        List<String> codes = enable(signIn());
        String recovery = codes.getFirst();

        MockHttpSession a = (MockHttpSession) mvc.perform(formLogin("/admin/login").user(NAME).password(PASSWORD))
                .andReturn().getRequest().getSession(false);
        mvc.perform(post("/admin/2fa").session(a).with(csrf()).param("code", " " + recovery.toUpperCase() + " "))
                .andExpect(redirectedUrl("/admin/overview"));

        MockHttpSession b = (MockHttpSession) mvc.perform(formLogin("/admin/login").user(NAME).password(PASSWORD))
                .andReturn().getRequest().getSession(false);
        mvc.perform(post("/admin/2fa").session(b).with(csrf()).param("code", recovery))
                .andExpect(redirectedUrl("/admin/2fa?error"));

        assertThat(db.sql("SELECT summary FROM admin_audit WHERE action = 'RECOVERY_CODE_USED' AND actor = :n")
                .param("n", NAME).query(String.class).single()).contains("7 left");
    }

    // ── turning it off ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("turning it off needs the password and a code from the app, not a recovery code")
    void disabling() throws Exception {
        MockHttpSession session = signIn();
        List<String> codes = enable(session);
        forgetLastStep();

        mvc.perform(post("/admin/security/two-factor/disable").session(session).with(csrf())
                        .param("currentPassword", PASSWORD).param("code", codes.getFirst()))
                .andExpect(flash().attributeExists("warning"));
        assertThat(storedSecret()).isNotNull();

        mvc.perform(post("/admin/security/two-factor/disable").session(session).with(csrf())
                        .param("currentPassword", PASSWORD).param("code", codeNow(storedSecret(), 0)))
                .andExpect(flash().attributeExists("message"));
        assertThat(storedSecret()).isNull();
        assertThat(db.sql("SELECT count(*) FROM admin_recovery_code rc JOIN admin_user a USING (admin_id) WHERE a.username = :n")
                .param("n", NAME).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("another administrator can remove it, which also signs the account out")
    void adminReset() throws Exception {
        MockHttpSession theirs = signIn();
        enable(theirs);
        UUID id = admins.findByUsernameIgnoreCase(NAME).orElseThrow().getAdminId();

        mvc.perform(post("/admin/users/" + id + "/two-factor/reset")
                        .with(user("testadmin").roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("message"));

        assertThat(storedSecret()).isNull();
        mvc.perform(get("/admin/players").session(theirs)).andExpect(redirectedUrl("/admin/login?revoked"));
        assertThat(db.sql("SELECT count(*) FROM admin_audit WHERE action = 'TWO_FACTOR_RESET' AND target = :n")
                .param("n", NAME).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("an account without two-factor signs in exactly as before")
    void unaffectedWithoutIt() throws Exception {
        mvc.perform(formLogin("/admin/login").user(NAME).password(PASSWORD))
                .andExpect(redirectedUrl("/admin/overview"));
    }
}
