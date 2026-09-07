package com.tarikusta.spacesurvivors.progress;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data generates the implementation of this interface at startup.
 *
 * <p>{@code findById}, {@code save}, {@code delete} and the rest arrive from
 * {@link JpaRepository} without a line of SQL. Anything beyond them is either derived
 * from a method name or written as an explicit {@code @Query}.</p>
 */
public interface PlayerProgressRepository extends JpaRepository<PlayerProgress, UUID> {
}
