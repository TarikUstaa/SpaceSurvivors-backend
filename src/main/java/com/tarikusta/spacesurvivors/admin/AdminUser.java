package com.tarikusta.spacesurvivors.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code admin_user} row: a person who administers the game.
 *
 * <p>Deliberately not a {@code PlayerProfile} with a flag — see V3__admin_user.sql for why
 * the two identities are kept apart.</p>
 */
@Entity
@Table(name = "admin_user")
public class AdminUser {

    @Id
    @GeneratedValue
    @Column(name = "admin_id", updatable = false)
    private UUID adminId;

    @Column(nullable = false, updatable = false)
    private String username;

    /**
     * BCrypt hash of the chosen password. The plaintext exists only for as long as it takes
     * to hash it, and never reaches a field, a log line or a getter.
     */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /**
     * Stored as the bare name ({@code ADMIN}, {@code SUPPORT}); V5 constrains the column to that
     * list. A {@code String} field rather than {@code @Enumerated}, so that reading a row can
     * never fail on a value the enum lacks — the CHECK already guarantees there is none, and an
     * account that throws while loading is an account that cannot sign in to fix itself.
     */
    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private boolean enabled;

    /**
     * Set when somebody other than the owner chose the current password — account creation and
     * password resets. Cleared only by the owner choosing their own. See V5__admin_roles.sql.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /**
     * Raised whenever every existing session of this account should end — see
     * V8__admin_session_epoch.sql and {@link AdminSessionGuard}.
     */
    @Column(name = "session_epoch", nullable = false)
    private int sessionEpoch;

    /** Base32 TOTP secret, or null when two-factor is off. See V9__admin_two_factor.sql. */
    @Column(name = "totp_secret")
    private String totpSecret;

    /** The 30-second window of the last code accepted; a code must be from a later one. */
    @Column(name = "totp_last_step")
    private Long totpLastStep;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Updated on every successful sign-in. Its value is not security — an attacker who is
     * already in can sign in too — but it is the cheapest way to notice an account nobody
     * uses any more, which is the one that should be disabled.
     */
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected AdminUser() {
        // JPA needs a no-arg constructor.
    }

    public AdminUser(String username, String passwordHash, AdminRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role.name();
        this.enabled = true;
    }

    /** Identity is the primary key and nothing else — same reasoning as PlayerProfile. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AdminUser that)) {
            return false;
        }
        return adminId != null && Objects.equals(adminId, that.adminId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public UUID getAdminId() {
        return adminId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Replace the stored hash. Takes a hash and not a password, deliberately: this class has
     * no encoder and should not acquire one, so the only value it can be handed is one that
     * has already been through {@code PasswordEncoder}. A setter taking plaintext is a
     * setter somebody eventually calls with plaintext.
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    /** Takes the enum, never a string, so no code path can write a role V5 would refuse. */
    public void setRole(AdminRole role) {
        this.role = role.name();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean hasTwoFactor() {
        return totpSecret != null;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public Long getTotpLastStep() {
        return totpLastStep;
    }

    /** Turn two-factor on with this secret, or off with null. Resets the replay guard either way. */
    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
        this.totpLastStep = null;
    }

    public void setTotpLastStep(Long totpLastStep) {
        this.totpLastStep = totpLastStep;
    }

    public int getSessionEpoch() {
        return sessionEpoch;
    }

    /** Signs out every session of this account on its next request. */
    public void endAllSessions() {
        sessionEpoch++;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}
