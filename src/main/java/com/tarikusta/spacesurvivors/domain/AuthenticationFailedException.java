package com.tarikusta.spacesurvivors.domain;

/**
 * The credential offered does not match the one on record.
 *
 * <p>Carries no detail about <em>why</em> on purpose: telling a caller whether a device is
 * registered, or whether it was the secret that was wrong, hands an attacker a way to
 * enumerate accounts one request at a time.</p>
 */
public class AuthenticationFailedException extends DomainException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
