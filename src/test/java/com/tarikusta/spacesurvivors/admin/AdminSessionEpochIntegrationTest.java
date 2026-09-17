package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
 * A password change ends the account's <em>other</em> sessions, and only those.
 *
 * <p>Real sign-ins throughout: the property under test lives in the session a sign-in creates, so a
 * principal installed by {@code with(user(...))} would prove nothing.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminSessionEpochIntegrationTest {

    private static final String NAME = "epoch-user";
    private static final String PASSWORD = "epoch-password-original";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AdminUserRepository admins;

    @Autowired
    private PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        admins.save(new AdminUser(NAME, encoder.encode(PASSWORD), AdminRole.SUPPORT));
    }

    private MockHttpSession signIn(String password) throws Exception {
        return (MockHttpSession) mvc.perform(formLogin("/admin/login").user(NAME).password(password))
                .andExpect(authenticated())
                .andReturn().getRequest().getSession(false);
    }

    @Test
    @DisplayName("changing the password keeps this session and signs out every other one")
    void ownChangeEndsTheOthers() throws Exception {
        MockHttpSession laptop = signIn(PASSWORD);
        MockHttpSession stolen = signIn(PASSWORD);

        mvc.perform(get("/admin/players").session(stolen)).andExpect(status().isOk());

        mvc.perform(post("/admin/password").session(laptop).with(csrf())
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", "epoch-password-replaced")
                        .param("confirmPassword", "epoch-password-replaced"))
                .andExpect(flash().attributeExists("message"));

        mvc.perform(get("/admin/players").session(laptop)).andExpect(status().isOk());
        mvc.perform(get("/admin/players").session(stolen))
                .andExpect(redirectedUrl("/admin/login?revoked"));
    }

    @Test
    @DisplayName("a refused password change ends nothing")
    void refusedChangeEndsNothing() throws Exception {
        MockHttpSession a = signIn(PASSWORD);
        MockHttpSession b = signIn(PASSWORD);

        mvc.perform(post("/admin/password").session(a).with(csrf())
                        .param("currentPassword", "wrong")
                        .param("newPassword", "epoch-password-replaced")
                        .param("confirmPassword", "epoch-password-replaced"))
                .andExpect(flash().attributeExists("warning"));

        mvc.perform(get("/admin/players").session(b)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an administrator resetting the password signs out the account's open sessions")
    void resetEndsTheirSessions() throws Exception {
        MockHttpSession theirs = signIn(PASSWORD);
        UUID id = admins.findByUsernameIgnoreCase(NAME).orElseThrow().getAdminId();

        mvc.perform(post("/admin/users/" + id + "/password")
                        .with(user("testadmin").roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("issued"));

        mvc.perform(get("/admin/players").session(theirs))
                .andExpect(redirectedUrl("/admin/login?revoked"));
        assertThat(admins.findById(id).orElseThrow().getSessionEpoch()).isEqualTo(1);
    }
}
