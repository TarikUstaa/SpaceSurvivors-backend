package com.tarikusta.spacesurvivors.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One place that turns exceptions from any controller into an HTTP error response.
 * {@code @RestControllerAdvice} = "applies across every @RestController".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Errors we raise deliberately, with the status we want. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    /**
     * Raised by Spring when a {@code @Valid @RequestBody} fails its constraints.
     * Without this handler the client just gets a bare 400; here we say which field
     * was wrong, which is what makes the API usable from Postman and from Unity.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fields.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid request body",
                "fields", fields));
    }

    /**
     * Anything the database refuses. The message can carry SQL, column names and
     * constraint names, so it is logged and never returned.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDatabase(DataAccessException e) {
        log.error("database call failed", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "storage unavailable"));
    }

    /**
     * The backstop. Without it an unforeseen failure falls through to Spring's default
     * error body, which leaks the request path and whatever the container decides to
     * include. A 500 should say nothing except that it happened; the detail belongs in
     * the log, where it is useful and not public.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal error"));
    }
}
