package com.tarikusta.spacesurvivors.exception;

/** The payload is bigger than this application is willing to store. */
public class TooLargeException extends DomainException {

    public TooLargeException(String message) {
        super(message);
    }
}
