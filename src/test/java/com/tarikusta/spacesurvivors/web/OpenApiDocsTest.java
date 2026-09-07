package com.tarikusta.spacesurvivors.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The generated API description actually describes this API.
 *
 * <p>Worth a test because the document is derived from the controllers: if an endpoint is
 * renamed or removed, this notices, where a hand-written spec would simply go stale.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("every endpoint appears, and the credential is declared")
    void documentsTheApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("SpaceSurvivors backend"))
                .andExpect(jsonPath("$.paths['/v1/player']").exists())
                .andExpect(jsonPath("$.paths['/v1/progress']").exists())
                .andExpect(jsonPath("$.paths['/v1/leaderboard']").exists())
                .andExpect(jsonPath("$.paths['/health']").exists())
                // Declared explicitly because the header is read by a filter, so no
                // controller signature mentions it and nothing would infer it.
                .andExpect(jsonPath("$.components.securitySchemes.deviceAuth.scheme").value("Device"));
    }
}
