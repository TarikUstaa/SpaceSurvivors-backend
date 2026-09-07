package com.tarikusta.spacesurvivors.web;

import com.tarikusta.spacesurvivors.progress.ProgressController;
import com.tarikusta.spacesurvivors.progress.ProgressService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ordinary ways a request can be wrong, all of which are the caller's mistake and
 * none of which is a server fault.
 *
 * <p>These exist because they were all answering <b>500</b>. A bare
 * {@code @ExceptionHandler(Exception.class)} sits in front of every exception Spring MVC
 * raises, so a mistyped URL, a wrong verb and an unreadable body were each reported as a
 * server failure and logged at ERROR — turning any bot probing for {@code /wp-admin} into
 * error-log noise. Extending {@code ResponseEntityExceptionHandler} fixed it; these tests
 * are here so it stays fixed.</p>
 */
@WebMvcTest(ProgressController.class)
@AutoConfigureMockMvc(addFilters = false)   // security is tested on its own, not here
@Import(ApiExceptionHandler.class)
class ApiExceptionHandlerTest {


    private static final UUID PLAYER = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProgressService progress;

    /**
     * A slice test runs without the filter chain, so nothing has populated the security
     * context. The controllers take {@code @CurrentPlayer}, which reads the verified token
     * from there — supplying one directly keeps these tests about status mapping instead of
     * about authentication, which has its own tests.
     */
    @BeforeEach
    void authenticate() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(PLAYER.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an unknown path is 404, not a server error")
    void unknownPath() throws Exception {
        mvc.perform(get("/v1/nothing-here"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a verb the endpoint does not serve is 405")
    void wrongMethod() throws Exception {
        mvc.perform(delete("/v1/progress"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("a body the endpoint cannot accept is 415")
    void wrongContentType() throws Exception {
        mvc.perform(put("/v1/progress")
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("a body that is not valid JSON is 400")
    void unreadableBody() throws Exception {
        mvc.perform(put("/v1/progress")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }
}
