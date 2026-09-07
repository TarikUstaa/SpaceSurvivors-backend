package com.tarikusta.spacesurvivors.web;

import com.tarikusta.spacesurvivors.domain.AlreadyTakenException;
import com.tarikusta.spacesurvivors.domain.InvalidInputException;
import com.tarikusta.spacesurvivors.domain.NotFoundException;
import com.tarikusta.spacesurvivors.domain.RuleViolationException;
import com.tarikusta.spacesurvivors.domain.TooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single place where a failure becomes an HTTP response.
 *
 * <p>Services throw {@link com.tarikusta.spacesurvivors.domain.DomainException}s that say
 * what went wrong in the application's own words; the choice of status code is made here
 * and nowhere else. That is what lets the service layer stay free of Spring web types.</p>
 *
 * <p>Bodies are {@link ProblemDetail} (RFC 9457), which Spring builds for us and clients
 * can rely on having a stable shape.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
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
     * Raised by Spring when a {@code @Valid @RequestBody} fails its constraints. The
     * offending field names are included: without them a 400 tells a client nothing.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "invalid request body");
        detail.setProperty("fields", fields);
        return detail;
    }

    /**
     * Raised by Hibernate's {@code @Version} check when a row changed between our read
     * and our write. The service already answers the ordinary stale-version case with a
     * 409 carrying the server's copy; this covers only the narrow race that slips past
     * it, and cannot carry a body — the transaction is rollback-only by the time we get
     * here. The client's response is the same either way: re-read and merge.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail concurrentWrite(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "the record changed while this write was in flight");
    }

    /**
     * Anything the database refuses. Its message can carry SQL, column and constraint
     * names, so it is logged and never returned.
     */
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail database(DataAccessException e) {
        log.error("database call failed", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "storage unavailable");
    }

    /**
     * The backstop. Without it an unforeseen failure falls through to the container's
     * default error page. A 500 should say only that it happened; the detail belongs in
     * the log, where it is useful and not public.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
