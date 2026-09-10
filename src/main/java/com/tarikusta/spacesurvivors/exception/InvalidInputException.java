package com.tarikusta.spacesurvivors.exception;

/**
 * The input is malformed — a name of the wrong length, a progress blob that is not an
 * object. Distinct from {@link RuleViolationException}: this is "I cannot make sense of
 * this", not "I understood it and will not accept it".
 */
public class InvalidInputException extends DomainException {

    public InvalidInputException(String message) {
        super(message);
    }
}
