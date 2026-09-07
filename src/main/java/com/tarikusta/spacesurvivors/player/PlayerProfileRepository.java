package com.tarikusta.spacesurvivors.player;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Three kinds of query live here, and the mix is the point — Spring Data covers the
 * ordinary cases and steps aside for the two that need Postgres.
 */
public interface PlayerProfileRepository extends JpaRepository<PlayerProfile, UUID> {

    /**
     * Derived query: Spring Data reads the method name and writes the SQL. No annotation,
     * no body — {@code findBy} + a property name is the whole specification.
     */
    Optional<PlayerProfile> findByDeviceId(String deviceId);

    /**
     * Create the profile unless the device or the name is already taken.
     *
     * <p>Native because {@code ON CONFLICT DO NOTHING} is Postgres, and JPA has no way to
     * express it. That matters beyond convenience: it returns zero rows instead of
     * raising, and a raised constraint violation would abort the surrounding transaction,
     * making the caller's retry impossible. Mixing JPA with native SQL where the database
     * does something JPA cannot is normal, not a workaround.</p>
     *
     * <p>{@code flushAutomatically} pushes pending changes out first and
     * {@code clearAutomatically} drops the persistence context afterwards, so a later read
     * sees the row this statement wrote rather than a cached absence.</p>
     *
     * @return 1 if the row was created, 0 if something was taken
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO player_profile (device_id, display_name, last_ip, device_secret_hash)
            VALUES (:deviceId, :displayName, CAST(:ip AS inet), :secretHash)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfFree(@Param("deviceId") String deviceId,
                     @Param("displayName") String displayName,
                     @Param("ip") String ip,
                     @Param("secretHash") String secretHash);

    /**
     * Record that we just saw this player, at most once every five minutes.
     *
     * <p>Native for the interval comparison, and because this runs on every authenticated
     * request — reads included. Writing each time would mean a row update and a WAL record
     * per API call for a value nobody needs to the second. {@code COALESCE} keeps the last
     * known address when the current request has no usable one.</p>
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE player_profile
               SET last_ip = COALESCE(CAST(:ip AS inet), last_ip)
             WHERE player_id = :playerId
               AND updated_at < now() - interval '5 minutes'
            """, nativeQuery = true)
    void touch(@Param("playerId") UUID playerId, @Param("ip") String ip);
}
