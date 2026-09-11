package com.tarikusta.spacesurvivors.web;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * With the documentation switched off — the way the deployment runs it — an unauthenticated
 * caller cannot read it.
 *
 * <p>Worth its own class because the guarantee is split across two files that only agree by
 * intention: {@code springdoc.api-docs.enabled=false} lives in application-prod.properties,
 * and the decision to leave those paths off the unauthenticated allow-list lives in Java, in
 * {@code SecurityConfig}. Both were edited together; nothing except this test would notice a
 * later change that re-published the map of every endpoint to the internet.</p>
 *
 * <p>Only the property is overridden, not the whole prod profile: that one also demands a
 * separate Flyway account and a proxy in front, neither of which exists in a test. The cost
 * is a second application context and therefore a second container (see {@link DatabaseTest})
 * — paid deliberately, because asserting this against the same context that has the docs
 * turned on would be asserting nothing.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
})
class OpenApiDocsDisabledTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("the API document is not served to an anonymous caller")
    void hidesTheDocument() throws Exception {
        // Deliberately not asserting a specific status. Two mechanisms can refuse this — a 404
        // because springdoc is not serving it, or a 401 because it is no longer on the
        // allow-list — and the guarantee being tested is that the body does not come back, not
        // which of the two said no.
        int status = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(200);
    }

    @Test
    @DisplayName("the browsable UI is not served either")
    void hidesTheUi() throws Exception {
        int status = mvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(200);
    }

    @Test
    @DisplayName("turning the docs off does not take the rest of the API with it")
    void leavesTheApiAlone() throws Exception {
        // The failure this guards against is a matcher change that refuses more than it meant
        // to. /health is public and must stay public whatever the docs are doing.
        mvc.perform(get("/health")).andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
    }
}
