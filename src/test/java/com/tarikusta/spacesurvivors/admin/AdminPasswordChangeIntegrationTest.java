package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changing the password through the page, and then actually signing in with it.
 *
 * <p>{@code AdminAccountServiceTest} already proves the hash is replaced correctly. What only
 * a test at this level can show is that the replacement is the credential the login path
 * reads — the service could be flawless and the change still worthless if authentication
 * consulted something else. That is the last assertion in the first test, and it is the
 * reason this class exists.</p>
 *
 * <p>{@code @Transactional} rolls the change back afterwards, so the shared administrator this
 * context bootstraps still has its original password for every other test.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminPasswordChangeIntegrationTest {

    private static final String ADMIN = "testadmin";
    private static final String CURRENT = "test-admin-password";
    private static final String REPLACEMENT = "a-replacement-password";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("the new password is the one that signs in afterwards")
    void changesTheCredentialThatLoginUses() throws Exception {
        mvc.perform(post("/admin/password")
                        .param("currentPassword", CURRENT)
                        .param("newPassword", REPLACEMENT)
                        .param("confirmPassword", REPLACEMENT)
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/password"))
                .andExpect(flash().attributeExists("message"));

        // The old password is refused...
        mvc.perform(formLogin("/admin/login").user(ADMIN).password(CURRENT))
                .andExpect(unauthenticated());

        // ...and the new one is accepted. Both halves, because a change that broke sign-in
        // entirely would pass the first assertion on its own.
        mvc.perform(formLogin("/admin/login").user(ADMIN).password(REPLACEMENT))
                .andExpect(authenticated().withUsername(ADMIN));
    }

    @Test
    @DisplayName("a mistyped confirmation changes nothing")
    void refusesAMistypedConfirmation() throws Exception {
        mvc.perform(post("/admin/password")
                        .param("currentPassword", CURRENT)
                        .param("newPassword", REPLACEMENT)
                        .param("confirmPassword", REPLACEMENT + "-oops")
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));

        mvc.perform(formLogin("/admin/login").user(ADMIN).password(CURRENT))
                .andExpect(authenticated());
    }

    @Test
    @DisplayName("a wrong current password changes nothing, even from a valid session")
    void refusesAWrongCurrentPassword() throws Exception {
        mvc.perform(post("/admin/password")
                        .param("currentPassword", "not-my-password")
                        .param("newPassword", REPLACEMENT)
                        .param("confirmPassword", REPLACEMENT)
                        .with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("warning"));

        // This is the whole point of asking for it: whoever holds the session cannot turn it
        // into permanent ownership of the account.
        mvc.perform(formLogin("/admin/login").user(ADMIN).password(CURRENT))
                .andExpect(authenticated());
        mvc.perform(formLogin("/admin/login").user(ADMIN).password(REPLACEMENT))
                .andExpect(unauthenticated());
    }

    @Test
    @DisplayName("the page is not reachable without signing in")
    void requiresAnAdmin() throws Exception {
        mvc.perform(post("/admin/password")
                        .param("currentPassword", CURRENT)
                        .param("newPassword", REPLACEMENT)
                        .param("confirmPassword", REPLACEMENT)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        mvc.perform(formLogin("/admin/login").user(ADMIN).password(CURRENT))
                .andExpect(authenticated());
    }

    @Test
    @DisplayName("a change without a CSRF token is refused")
    void requiresCsrf() throws Exception {
        mvc.perform(post("/admin/password")
                        .param("currentPassword", CURRENT)
                        .param("newPassword", REPLACEMENT)
                        .param("confirmPassword", REPLACEMENT)
                        .with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isForbidden());

        mvc.perform(formLogin("/admin/login").user(ADMIN).password(CURRENT))
                .andExpect(authenticated());
    }
}
