package com.tarikusta.spacesurvivors.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the limiter is actually in the chain — not merely correct in isolation.
 *
 * <p>{@link RateLimitFilterTest} drives the filter directly, which proves the counting is
 * right and proves nothing about whether the running application ever calls it. A filter
 * registered at the wrong order, or not registered at all, passes every one of those tests
 * while leaving the endpoint exactly as exposed as before. This is the same gap that let
 * D20 through: a mocked collaborator confirms the call we expect, only the real thing
 * confirms the call has an effect.</p>
 *
 * <p>The capacity is dropped to two here rather than firing thirty requests at the real
 * setting, so the test states its intent in one line and stays fast.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.ratelimit.enabled=true",
        "app.ratelimit.capacity=2",
        "app.ratelimit.window=1m"
})
@Transactional
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mvc;

    /** A body that is well formed but wrong, so a refusal can only be the limiter. */
    private String credential() {
        return """
                {"deviceId":"%s","deviceSecret":"a-device-secret-long-enough-to-matter-32"}
                """.formatted(UUID.randomUUID());
    }

    private void requestToken(org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        mvc.perform(post("/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credential()))
                .andExpect(expected);
    }

    @Test
    @DisplayName("the third token request in a burst of two-per-minute is refused with 429")
    void burstBeyondTheCapacityIsRefused() throws Exception {
        // Two allowed. Each registers a new device, so these are 200s — the point is only
        // that they are not 429s.
        requestToken(status().isOk());
        requestToken(status().isOk());

        // The third has no token left to spend, and is stopped before the controller — so
        // before BCrypt, which is the entire reason this filter exists.
        mvc.perform(post("/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credential()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(header().string("RateLimit-Limit", "2"));
    }
}
