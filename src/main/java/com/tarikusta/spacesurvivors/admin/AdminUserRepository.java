package com.tarikusta.spacesurvivors.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

    /**
     * Case-insensitive, to match the unique index the migration builds on
     * {@code lower(username)}. If this lookup were case-sensitive while the index was not,
     * an account could exist that nobody could sign in to — the row would block the name
     * from being created again, and the query would never find it.
     */
    Optional<AdminUser> findByUsernameIgnoreCase(String username);
}
