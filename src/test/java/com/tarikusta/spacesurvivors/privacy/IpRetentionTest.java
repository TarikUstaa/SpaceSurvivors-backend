package com.tarikusta.spacesurvivors.privacy;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DatabaseTest
@Transactional
class IpRetentionTest {

    @Autowired
    private IpRetention retention;

    @Autowired
    private JdbcClient db;

    private UUID player(int daysAgo) {
        // updated_at set on insert, where the trigger does not run.
        return db.sql("""
                        INSERT INTO player_profile (device_id, display_name, device_secret_hash, last_ip, country, updated_at)
                        VALUES (:d, :n, 'x', cast('203.0.113.9' as inet), 'TR', now() - make_interval(days => :ago))
                        RETURNING player_id""")
                .param("d", "ret-" + UUID.randomUUID()).param("n", "Ret" + (int) (Math.random() * 1_000_000))
                .param("ago", daysAgo)
                .query(UUID.class).single();
    }

    private Map<String, Object> row(UUID id) {
        return db.sql("SELECT last_ip::text AS ip, country, updated_at FROM player_profile WHERE player_id = :p")
                .param("p", id).query().singleRow();
    }

    @Test
    @DisplayName("an address older than the retention period is cleared; a recent one stays")
    void clearsOnlyOldAddresses() {
        UUID gone = player(retention.retentionDays() + 10);
        UUID recent = player(3);

        assertThat(retention.clear()).isGreaterThanOrEqualTo(1);

        assertThat(row(gone).get("ip")).isNull();
        assertThat(row(recent).get("ip")).isNotNull();
    }

    @Test
    @DisplayName("clearing does not make the player look recently active, and keeps the country")
    void keepsLastSeenAndCountry() {
        UUID gone = player(retention.retentionDays() + 10);
        OffsetDateTime before = db.sql("SELECT updated_at FROM player_profile WHERE player_id = :p")
                .param("p", gone).query(OffsetDateTime.class).single();

        retention.clear();

        assertThat(db.sql("SELECT updated_at FROM player_profile WHERE player_id = :p")
                .param("p", gone).query(OffsetDateTime.class).single()).isEqualTo(before);
        assertThat(row(gone).get("country")).isEqualTo("TR");
    }

    @Test
    @DisplayName("running it again finds nothing more to do")
    void isIdempotent() {
        player(retention.retentionDays() + 10);
        retention.clear();
        assertThat(retention.clear()).isZero();
    }
}
