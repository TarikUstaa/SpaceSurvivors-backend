package com.tarikusta.spacesurvivors.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renaming against a real database.
 *
 * <p>Every existing test for this path used a mocked repository, so the UPDATE Hibernate
 * actually emits for a {@link PlayerProfile} had never once been sent to Postgres. It
 * turned out not to work, and only an integration test could have said so.</p>
 */
@SpringBootTest
@Transactional
class PlayerRenameIntegrationTest {

    private static final String SECRET = "an-integration-test-secret-32-chars-long";

    @Autowired
    private PlayerService players;

    @Test
    @DisplayName("a rename reaches the database")
    void renamesAnExistingPlayer() {
        UUID playerId = players.authenticateDevice("test-" + UUID.randomUUID(), SECRET, "127.0.0.1");
        String name = "Renamed" + (int) (Math.random() * 899_999 + 100_000);

        Player renamed = players.rename(playerId, name);

        assertThat(renamed.displayName()).isEqualTo(name);
    }
}
