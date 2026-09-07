package com.tarikusta.spacesurvivors.progress;

import com.tarikusta.spacesurvivors.auth.Caller;
import com.tarikusta.spacesurvivors.domain.NotFoundException;
import com.tarikusta.spacesurvivors.domain.TooLargeException;
import com.tarikusta.spacesurvivors.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
@Import(ApiExceptionHandler.class)
class ProgressControllerTest {

    private static final String DEVICE = "X-Device-Id";
    private static final String BODY = """
            {"progress":{"wallet":100},"version":1}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private ProgressService progress;

    @Test
    void loadReturnsTheSaveAndItsVersion() throws Exception {
        when(progress.load(any(Caller.class))).thenReturn(
                new ProgressDtos.ProgressView(json.readTree("""
                        {"wallet":100}"""), 3));

        mvc.perform(get("/v1/progress").header(DEVICE, "dev-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.progress.wallet").value(100));
    }

    @Test
    void loadIs404WhenNothingIsStored() throws Exception {
        when(progress.load(any(Caller.class))).thenThrow(new NotFoundException("no progress stored yet"));

        mvc.perform(get("/v1/progress").header(DEVICE, "dev-a"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("no progress stored yet"));
    }

    @Test
    void saveIs200WithTheNewVersion() throws Exception {
        when(progress.save(any(Caller.class), any())).thenReturn(new ProgressService.SaveOutcome.Accepted(4));

        mvc.perform(put("/v1/progress").header(DEVICE, "dev-a")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void saveIs409AndHandsBackTheServerCopyWhenTheVersionIsStale() throws Exception {
        when(progress.save(any(Caller.class), any())).thenReturn(
                new ProgressService.SaveOutcome.Conflict(7, json.readTree("""
                        {"wallet":999}""")));

        mvc.perform(put("/v1/progress").header(DEVICE, "dev-a")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.serverVersion").value(7))
                .andExpect(jsonPath("$.progress.wallet").value(999));
    }

    @Test
    void saveIs413WhenTheBlobIsTooBig() throws Exception {
        when(progress.save(any(Caller.class), any())).thenThrow(new TooLargeException("progress exceeds 64 KB"));

        mvc.perform(put("/v1/progress").header(DEVICE, "dev-a")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void saveIs400AndNamesTheFieldWhenTheVersionIsNegative() throws Exception {
        mvc.perform(put("/v1/progress").header(DEVICE, "dev-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"progress":{"wallet":1},"version":-1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.version").exists());
    }
}
