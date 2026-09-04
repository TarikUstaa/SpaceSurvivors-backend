package com.tarikusta.spacesurvivors.profile;

import com.tarikusta.spacesurvivors.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * HTTP layer for the cloud save: read the request, call the service, choose the
 * status code + response shape. {@code userId} is supplied by {@link
 * com.tarikusta.spacesurvivors.auth.DevAuthFilter} via {@code @RequestAttribute}.
 */
@RestController
@RequestMapping("/v1/profile")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    /** GET /v1/profile -> 200 {profile, version}  |  404 if nothing saved yet. */
    @GetMapping
    public ResponseEntity<Object> load(@RequestAttribute("userId") String userId) {
        return service.load(userId)
                .<ResponseEntity<Object>>map(p -> ResponseEntity.ok(
                        Map.of("profile", p.profile(), "version", p.version())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "no profile stored yet")));
    }

    /**
     * PUT /v1/profile  body: {"profile": {...}, "version": N}
     *  - 200 {version}                     wrote successfully
     *  - 409 {serverVersion, profile}      the client's version was stale
     */
    @PutMapping
    public ResponseEntity<Object> save(@RequestAttribute("userId") String userId,
                                       @RequestBody SaveRequest body) {
        if (body.profile() == null || !body.profile().isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "profile must be a JSON object");
        }
        ProfileService.SaveOutcome outcome = service.save(userId, body.profile(), body.version());
        if (outcome.isConflict()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "serverVersion", outcome.version(),
                    "profile", outcome.conflictProfile()));
        }
        return ResponseEntity.ok(Map.of("version", outcome.version()));
    }

    /** Body of PUT /v1/profile. {@code version} defaults to 0 (a first upload). */
    public record SaveRequest(JsonNode profile, int version) {
    }
}
