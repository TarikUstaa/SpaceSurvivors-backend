package com.tarikusta.spacesurvivors.web;

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
}
