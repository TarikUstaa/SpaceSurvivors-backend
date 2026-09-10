package com.tarikusta.spacesurvivors.exception;

/** The thing being asked for does not exist. */
public class NotFoundException extends DomainException {

    public NotFoundException(String message) {
        super(message);
    }
}
