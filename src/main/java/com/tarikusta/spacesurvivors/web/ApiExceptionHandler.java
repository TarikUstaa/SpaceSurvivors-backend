package com.tarikusta.spacesurvivors.web;

import com.tarikusta.spacesurvivors.exception.AlreadyTakenException;
import com.tarikusta.spacesurvivors.exception.AuthenticationFailedException;
import com.tarikusta.spacesurvivors.exception.InvalidInputException;
import com.tarikusta.spacesurvivors.exception.NotFoundException;
import com.tarikusta.spacesurvivors.exception.RuleViolationException;
import com.tarikusta.spacesurvivors.exception.TooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single place where a failure becomes an HTTP response.
 *
 * <p>Services throw {@link com.tarikusta.spacesurvivors.exception.DomainException}s that say
 * what went wrong in the application's own words and carry no status code; choosing the
 * status happens here and nowhere else. That is what lets the service layer stay free of
 * Spring web types.</p>
 *
 * <p><b>Extending {@link ResponseEntityExceptionHandler} is not decoration.</b> Spring MVC
 * raises its own exceptions for the ordinary ways a request can be wrong — an unknown
 * path, a method the endpoint does not serve, an unreadable body, a content type nobody
 * accepts. Without the base class, the {@code Exception} catch-all below swallowed every
 * one of them: a request to a mistyped URL answered <em>500</em> instead of 404, and each
 * one was logged at ERROR, so any bot probing for {@code /wp-admin} filled the error log
 * with entries about nothing. The base class answers all of those correctly and leaves the
 * catch-all for what it is meant for — the failures we genuinely did not foresee.</p>
 *
 * <p>Every body is a {@link ProblemDetail} (RFC 9457), including the base class's, so a
 * client has exactly one error shape to parse.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // ── the application's own failures ─────────────────────────────────────────────

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * A device credential that did not check out. The message never distinguishes an
     * unknown device from a wrong secret: telling them apart would let someone enumerate
     * accounts one request at a time.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ProblemDetail authenticationFailed(AuthenticationFailedException e) {
        return problem(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(AlreadyTakenException.class)
    public ProblemDetail alreadyTaken(AlreadyTakenException e) {
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidInputException.class)
    public ProblemDetail invalidInput(InvalidInputException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Well formed, understood, and refused — which is exactly what 422 means. */
    @ExceptionHandler(RuleViolationException.class)
    public ProblemDetail ruleViolation(RuleViolationException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(TooLargeException.class)
    public ProblemDetail tooLarge(TooLargeException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
    }

    /**
     * Hibernate's {@code @Version} check, when a row changed between our read and our
     * write. The service already answers the ordinary stale-version case with a 409
     * carrying the server's copy; this covers only the narrow race that slips past it,
     * and cannot carry a body — the transaction is rollback-only by the time we are here.
     * The client's response is the same either way: re-read and merge.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail concurrentWrite(ObjectOptimisticLockingFailureException e) {
        // Debug rather than dropped: this is a race working as designed, but it is also the
        // only trace that it happened, and "how often do two devices write at once" is a
        // question worth being able to answer.
        log.debug("optimistic lock lost", e);
        return problem(HttpStatus.CONFLICT, "the record changed while this write was in flight");
    }

    /**
     * Anything the database refuses. Its message can carry SQL, column names and
     * constraint names, so it is logged and never returned. 503 rather than 500 because
     * the request itself was fine and retrying may well work.
     */
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail database(DataAccessException e) {
        log.error("database call failed", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "storage unavailable");
    }

    /**
     * The backstop, and now genuinely only that. A 500 should say only that it happened;
     * the detail belongs in the log, where it is useful and not public.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    // ── Spring MVC's own failures ──────────────────────────────────────────────────

    /**
     * A {@code @Valid @RequestBody} that failed its constraints. The base class would
     * answer a bare 400; naming the offending fields is what makes the response usable
     * from Postman and from the Unity client, so this override adds them.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "invalid request body");
        body.setProperty("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Everything else Spring MVC raises — 404, 405, 415, an unreadable body and the rest.
     * The base class has already chosen the right status; this only keeps the log honest.
     * A malformed request is the caller's mistake, not an incident, so it is logged at
     * debug: a mistyped URL should not look like a fault in the service.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode status,
                                                             WebRequest request) {
        log.debug("request rejected with {}: {}", status, e.getMessage());
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
