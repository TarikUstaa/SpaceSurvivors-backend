package com.tarikusta.spacesurvivors.domain;

/**
 * Base for the things this application refuses to do.
 *
 * <p>These carry no HTTP status and no Spring web types, so a service can say what went
 * wrong without knowing it is being called over HTTP at all. Turning each one into a
 * status code is the web layer's job and happens in a single place —
 * {@code ApiExceptionHandler}. Swap the transport and the services do not change.</p>
 *
 * <p>Unchecked on purpose: these describe situations a caller cannot meaningfully
 * recover from mid-request, and forcing every layer to declare them would add noise
 * without adding safety.</p>
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
