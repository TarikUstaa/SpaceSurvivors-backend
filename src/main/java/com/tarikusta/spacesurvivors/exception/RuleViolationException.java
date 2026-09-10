package com.tarikusta.spacesurvivors.exception;

/**
 * The input is well formed but breaks a rule of the game — an unknown mode, a run the
 * game could not have produced. The caller understood the API; the content is the problem.
 */
public class RuleViolationException extends DomainException {

    public RuleViolationException(String message) {
        super(message);
    }
}
