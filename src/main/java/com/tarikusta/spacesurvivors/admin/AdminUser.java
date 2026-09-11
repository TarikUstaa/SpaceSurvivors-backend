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

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private boolean enabled;

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

    public AdminUser(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
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

    public boolean isEnabled() {
        return enabled;
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
