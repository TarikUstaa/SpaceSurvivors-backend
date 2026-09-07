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

    /** PUT /v1/progress -> 200 {version}, or 409 {serverVersion, progress} on a stale write. */
    @PutMapping
    public ResponseEntity<?> save(@RequestAttribute(Caller.ATTR) Caller caller,
                                  @Valid @RequestBody ProgressDtos.SaveRequest body) {
        return switch (progress.save(caller, body)) {
            case ProgressService.SaveOutcome.Accepted a ->
                    ResponseEntity.ok(new ProgressDtos.SaveAccepted(a.version()));
            case ProgressService.SaveOutcome.Conflict c ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new ProgressDtos.SaveConflict(c.serverVersion(), c.serverProgress()));
        };
    }
}
