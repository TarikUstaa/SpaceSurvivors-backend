-- V9__admin_two_factor.sql — a second factor for backoffice sign-in, per account, by choice.
--
-- A password alone is one guess, one leak or one reused credential away from somebody else's
-- session. A time-based one-time code (TOTP, RFC 6238) from an authenticator app adds a thing the
-- attacker would also have to hold. Opt-in per account rather than enforced: with a single
-- administrator, a lost phone under a mandatory policy is a locked backoffice, and recovery codes
-- are the only thing standing between that and a hand-written UPDATE.
--
-- totp_secret      the shared secret, base32. NULL means two-factor is off for this account.
--                  Stored readable, and that is a known trade: the server has to compute codes
--                  from it, so hashing is not an option, and encrypting it means a key that lives
--                  beside the database credentials on the same host — somebody who can read this
--                  column can, in practice, read that key too. What does protect it is the rest:
--                  the least-privilege role (D3/F19), Key Vault for the credentials, TLS.
--
-- totp_last_step   the 30-second window of the last code accepted. A code is refused unless its
--                  window is later — so a code read over somebody's shoulder, or replayed from a
--                  captured request, cannot be used a second time within its own lifetime.

ALTER TABLE admin_user
    ADD COLUMN totp_secret    text,
    ADD COLUMN totp_last_step bigint;

-- One-time recovery codes for a lost authenticator. BCrypt-hashed like passwords, because they are
-- passwords: each one alone completes a sign-in. used_at rather than a delete, so "a recovery code
-- was used, and when" stays answerable.
CREATE TABLE admin_recovery_code (
    code_id   bigserial   PRIMARY KEY,
    admin_id  uuid        NOT NULL REFERENCES admin_user (admin_id) ON DELETE CASCADE,
    code_hash text        NOT NULL,
    used_at   timestamptz
);

CREATE INDEX admin_recovery_code_by_admin ON admin_recovery_code (admin_id) WHERE used_at IS NULL;
