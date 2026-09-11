package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
// NOTE the package: Spring Security 7 moved these matchers from ...servlet.result to
// ...servlet.response. Every tutorial still shows the old one.
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The backoffice has its own door, and the game's key does not open it.
 *
 * <p>Two filter chains in one application is the kind of arrangement that looks right in the
 * configuration and is wrong in the running service — the failure modes are a chain matching
 * more than it meant to, or less. Both are invisible until someone tries the wrong door, so
 * the tests below try every wrong door on purpose.</p>
 *
 * <p>The administrator these tests sign in as is not inserted here. It is created by
 * {@link AdminBootstrap} from the credentials in application-test.properties, so a change
 * that broke the bootstrap would surface as a failure to sign in rather than as nothing.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
class AdminSecurityIntegrationTest {

    private static final String ADMIN = "testadmin";
    private static final String PASSWORD = "test-admin-password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    // ── the door itself ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous visitor is sent to the login page, not refused with a 401")
    void redirectsAnonymousToLogin() throws Exception {
        // The distinction is the whole reason for a second chain. The API answers a missing
        // credential with 401 because a program is reading it; a person gets a page they can
        // do something about.
        mvc.perform(get("/admin/players"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("the login page is reachable without signing in")
    void servesTheLoginPage() throws Exception {
        mvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    @DisplayName("the login page's stylesheet is reachable without signing in")
    void servesTheStylesheet() throws Exception {
        // Its own test because the failure is silent: the page still renders, just unstyled,
        // and a 401 on a stylesheet looks like nothing at all in a browser. The file sits
        // under /admin/, so it is the admin chain — not the API's — that has to allow it.
        mvc.perform(get("/admin/assets/admin.css"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("admin pages refuse to be framed")
    void cannotBeIframed() throws Exception {
        // Clickjacking is the attack a panel like this invites: a transparent iframe over a
        // page that says something harmless, positioned so the click lands on Delete. Spring
        // Security sends this header by default — asserted because "by default" is a thing
        // that a later .headers() customisation can quietly switch off.
        mvc.perform(get("/admin/login"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @DisplayName("correct credentials sign in and land on the player list")
    void signsIn() throws Exception {
        mvc.perform(formLogin("/admin/login").user(ADMIN).password(PASSWORD))
                .andExpect(authenticated().withUsername(ADMIN).withRoles("ADMIN"))
                .andExpect(redirectedUrl("/admin/players"));
    }

    @Test
    @DisplayName("a wrong password fails, and says only that")
    void refusesAWrongPassword() throws Exception {
        mvc.perform(formLogin("/admin/login").user(ADMIN).password("not-the-password"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/admin/login?error"));
    }

    @Test
    @DisplayName("an unknown username fails exactly the same way")
    void refusesAnUnknownUser() throws Exception {
        // Identical outcome to the wrong-password case, deliberately: a different message or
        // a different redirect would turn this form into a way to discover valid usernames.
        mvc.perform(formLogin("/admin/login").user("nobody").password(PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/admin/login?error"));
    }

    @Test
    @DisplayName("the player list renders once signed in")
    void showsPlayers() throws Exception {
        mvc.perform(get("/admin/players").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Players")));
    }

    @Test
    @DisplayName("a signed-in visitor without the ADMIN role is refused")
    void refusesTheWrongRole() throws Exception {
        // Being authenticated is not the same as being allowed. Today every admin_user row
        // carries ADMIN, so this is the test that keeps the rule real if a second role is
        // ever added.
        mvc.perform(get("/admin/players").with(user("someone").roles("PLAYER")))
                .andExpect(status().isForbidden());
    }

    // ── the two chains do not bleed into each other ────────────────────────────────

    @Test
    @DisplayName("a valid game token does not open the backoffice")
    void gameTokenIsNotAnAdminCredential() throws Exception {
        String token = obtainGameToken();

        // This is the assertion the whole milestone rests on. The token is genuine — the
        // API accepts it, as the next assertion shows — and it is still worth nothing here,
        // because the admin chain never consults a bearer token at all.
        mvc.perform(get("/admin/players").header("Authorization", "Bearer " + token))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));

        mvc.perform(get("/v1/player").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an admin session does not open the game API")
    void adminSessionIsNotAGameCredential() throws Exception {
        // The other direction, and the one that needs care to test honestly. The obvious
        // way — .with(user(ADMIN)) — installs a security context directly and proves
        // nothing, because no browser can do that; it is the request every real caller
        // makes that has to be tried. So: sign in for real, keep the session the sign-in
        // produced, and send it where it does not belong.
        MockHttpSession session = (MockHttpSession) mvc
                .perform(formLogin("/admin/login").user(ADMIN).password(PASSWORD))
                .andExpect(authenticated())
                .andReturn().getRequest().getSession(false);

        assertThat(session).isNotNull();

        // The control assertion, without which the next one means nothing: this session is
        // genuinely good for something. A dead session would refuse both requests and the
        // test would pass while proving the opposite of what it claims.
        mvc.perform(get("/admin/players").session(session))
                .andExpect(status().isOk());

        // And it is still not a credential for the game. The API chain is stateless — it
        // never reads a session — so the cookie an administrator carries is invisible there.
        mvc.perform(get("/v1/player").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("adding the admin chain did not stop the API refusing anonymous calls")
    void apiStillRefusesAnonymous() throws Exception {
        mvc.perform(get("/v1/player"))
                .andExpect(status().isUnauthorized());
    }

    // ── CSRF ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a POST without a CSRF token is rejected")
    void requiresCsrf() throws Exception {
        // formLogin() above attaches one for us, which is convenient and hides this. Posting
        // by hand is the only way to see that the protection is actually switched on — and
        // it is the protection that stops another site from signing an administrator out, or
        // worse, now that this section has buttons that change things.
        //
        // The refusal is a redirect rather than a 403 because a stale token is something a
        // person can fix; StaleFormHandler explains why, and why only CSRF failures are
        // treated this way.
        mvc.perform(post("/admin/logout").with(user(ADMIN).roles("ADMIN")))
                .andExpect(redirectedUrl("/admin/login?expired"));

        mvc.perform(post("/admin/logout").with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private String obtainGameToken() throws Exception {
        String body = """
                {"deviceId":"admin-test-%s","deviceSecret":"a-device-secret-long-enough-to-matter-32"}"""
                .formatted(UUID.randomUUID());

        String response = mvc.perform(post("/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = json.readTree(response).get("token").asString();
        assertThat(token).isNotBlank();
        return token;
    }
}
