package com.tarikusta.spacesurvivors.progress;

import com.tarikusta.spacesurvivors.auth.Caller;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP for the cloud save. The only thing decided here is which status code a
 * {@link ProgressService.SaveOutcome} maps to — an HTTP concern, and the last one left
 * once every rule lives in the service.
 */
@RestController
@RequestMapping("/v1/progress")
public class ProgressController {

    private final ProgressService progress;

    public ProgressController(ProgressService progress) {
        this.progress = progress;
    }

    /** GET /v1/progress -> 200 {progress, version}, or 404 when nothing is stored. */
    @GetMapping
    public ProgressDtos.ProgressView load(@RequestAttribute(Caller.ATTR) Caller caller) {
        return progress.load(caller);
    }

    /**
     * PUT /v1/progress -> 200 {version}, or 409 {serverVersion, progress} on a stale write.
     *
     * <p>The only decision here is which status each outcome deserves. Building the bodies
     * belongs to the response types themselves, so this method never reaches inside an
     * outcome to read its fields.</p>
     */
    @PutMapping
    public ResponseEntity<Object> save(@RequestAttribute(Caller.ATTR) Caller caller,
                                       @Valid @RequestBody ProgressDtos.SaveRequest body) {
        return switch (progress.save(caller, body)) {
            case ProgressService.SaveOutcome.Accepted accepted ->
                    ResponseEntity.ok(ProgressDtos.SaveAccepted.of(accepted));
            case ProgressService.SaveOutcome.Conflict conflict ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(ProgressDtos.SaveConflict.of(conflict));
        };
    }
}
