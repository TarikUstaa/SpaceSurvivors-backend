package com.tarikusta.spacesurvivors.exception;

/** Someone else already holds the value being claimed — a display name, so far. */
public class AlreadyTakenException extends DomainException {

    public AlreadyTakenException(String message) {
        super(message);
    }
}
