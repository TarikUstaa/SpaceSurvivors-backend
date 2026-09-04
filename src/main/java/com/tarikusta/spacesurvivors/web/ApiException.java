package com.tarikusta.spacesurvivors.web;

import org.springframework.http.HttpStatus;

/**
 * Throw this anywhere inside a request to stop with a specific HTTP status.
 * {@link ApiExceptionHandler} catches it and writes the JSON error body.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
