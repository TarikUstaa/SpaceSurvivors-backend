package com.tarikusta.spacesurvivors.progress;

import com.tarikusta.spacesurvivors.exception.NotFoundException;
import com.tarikusta.spacesurvivors.exception.TooLargeException;
import com.tarikusta.spacesurvivors.web.ApiExceptionHandler;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web layer only: that each outcome becomes the right status code, and that a domain
 * exception thrown deep in a service still arrives as a sensible response.
 *
 * <p>No database and no real service — this is the contract the Unity client codes against.</p>
 */
@WebMvcTest(ProgressController.class)
@AutoConfigureMockMvc(addFilters = false)   // security is tested on its own, not here
@Import(ApiExceptionHandler.class)
// Same throwaway settings as every other test. These slices need no database, but
// without this they run on the "local" profile and read the git-ignored
// application-local.properties — which passes here and on no other machine.
@ActiveProfiles("test")
class ProgressControllerTest {

    private static final String BODY = """
            {"progress":{"wallet":100},"version":1}""";

    private static final UUID PLAYER = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

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
    void loadReturnsTheSaveAndItsVersion() throws Exception {
        when(progress.load(any(UUID.class))).thenReturn(
                new ProgressDtos.ProgressView(json.readTree("""
                        {"wallet":100}"""), 3));

        mvc.perform(get("/v1/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.progress.wallet").value(100));
    }

    @Test
    void loadIs404WhenNothingIsStored() throws Exception {
        when(progress.load(any(UUID.class))).thenThrow(new NotFoundException("no progress stored yet"));

        mvc.perform(get("/v1/progress"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("no progress stored yet"));
    }

    @Test
    void saveIs200WithTheNewVersion() throws Exception {
        when(progress.save(any(UUID.class), any())).thenReturn(new ProgressService.SaveOutcome.Accepted(4));

        mvc.perform(put("/v1/progress")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void saveIs409AndHandsBackTheServerCopyWhenTheVersionIsStale() throws Exception {
        when(progress.save(any(UUID.class), any())).thenReturn(
                new ProgressService.SaveOutcome.Conflict(7, json.readTree("""
                        {"wallet":999}""")));

        mvc.perform(put("/v1/progress")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.serverVersion").value(7))
                .andExpect(jsonPath("$.progress.wallet").value(999));
    }

    @Test
    void saveIs413WhenTheBlobIsTooBig() throws Exception {
        when(progress.save(any(UUID.class), any())).thenThrow(new TooLargeException("progress exceeds 64 KB"));

        mvc.perform(put("/v1/progress")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void saveIs400AndNamesTheFieldWhenTheVersionIsNegative() throws Exception {
        mvc.perform(put("/v1/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"progress":{"wallet":1},"version":-1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.version").exists());
    }
}
