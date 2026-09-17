package com.tarikusta.spacesurvivors.exception;

/**
 * The player exists and proved who they are, and has been suspended by an operator (V11).
 *
 * <p>Deliberately distinct from {@link AuthenticationFailedException}: that one must not reveal
 * whether an account exists, while this one is only ever thrown <em>after</em> the credential
 * checked out — the caller is the account holder, and telling them why they are refused is the
 * whole point.</p>
 */
public class AccountSuspendedException extends DomainException {

    private final String reason;

    public AccountSuspendedException(String reason) {
        super("this account is suspended");
        this.reason = reason;
    }

    /** The operator's reason, or null if none was given. */
    public String reason() {
        return reason;
    }
}
