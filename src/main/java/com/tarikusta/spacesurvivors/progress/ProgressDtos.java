package com.tarikusta.spacesurvivors.progress;

import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.JsonNode;

/**
 * Request and response shapes for {@code /v1/progress}.
 *
 * <p>Records rather than ad-hoc maps: the response shape is part of the API contract,
 * so it belongs in a named type the Unity client's DTOs can be checked against.</p>
 */
public final class ProgressDtos {

    private ProgressDtos() {
    }

    /**
     * Body of {@code PUT /v1/progress}: {@code {"progress": {...}, "version": N}}.
     * {@code version} is what the client last read; 0 means "I have never synced", so
     * a negative one is a client bug and deserves a 400 rather than a silent 409.
     *
     * <p>That {@code progress} must be a JSON <em>object</em> is checked in the service:
     * bean validation has no annotation for it, and it is a rule about the payload's
     * meaning rather than its shape.</p>
     */
    public record SaveRequest(JsonNode progress, @PositiveOrZero int version) {
    }

    /** 200 from GET — the save and the version to send back on the next write. */
    public record ProgressView(JsonNode progress, int version) {
    }

    /** 200 from PUT — the version the write produced. */
    public record SaveAccepted(int version) {
    }

    /** 409 from PUT — the caller's version was stale; here is what the server holds. */
    public record SaveConflict(int serverVersion, JsonNode progress) {
    }
}
