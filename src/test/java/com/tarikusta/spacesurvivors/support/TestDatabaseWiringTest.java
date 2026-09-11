package com.tarikusta.spacesurvivors.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the suite is talking to the throwaway container and not to the developer's own
 * Postgres.
 *
 * <p><b>This test exists because its absence would be invisible.</b> The machine that wrote
 * this has a Postgres running on the usual port with the right schema in it. If
 * {@code @ServiceConnection} were misconfigured and the datasource quietly fell back to
 * {@code localhost:5432}, every other test in the suite would still pass — against the
 * wrong database, on a laptop, in a way that would only surface as a total failure on the
 * first machine that did not happen to have one. A green suite would be evidence of
 * nothing.</p>
 *
 * <p>It is the same shape of check as the one in {@code RateLimitIntegrationTest}: when a
 * piece of wiring is what makes a feature real, assert the wiring, because the behaviour it
 * enables can be produced by accident.</p>
 */
@DatabaseTest
class TestDatabaseWiringTest {

    /** Where a developer's own server lives, and therefore what we must not be using. */
    private static final int LOCAL_POSTGRES_PORT = 5432;

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("the datasource points at the container, on its own ephemeral port")
    void connectsToTheContainer() throws Exception {
        assertThat(postgres.isRunning()).isTrue();

        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();

            // Testcontainers publishes on a random free port, so a container connection can
            // never be the one a developer's server would answer.
            assertThat(url)
                    .as("the suite must not be reaching a database somebody installed by hand")
                    .doesNotContain(":" + LOCAL_POSTGRES_PORT + "/")
                    .contains(":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/");
        }
    }

    @Test
    @DisplayName("the container starts empty, so the migrations run from nothing")
    void flywayBuildsTheSchemaFromScratch() {
        List<String> applied = db.sql("SELECT script FROM flyway_schema_history ORDER BY installed_rank")
                .query(String.class).list();

        // A container that started empty and now holds the schema means every migration was
        // exercised as a new deployment would meet it, not merely validated against a
        // database somebody had already migrated by hand months ago.
        //
        // The list is spelled out rather than loosely counted, and it is meant to be edited:
        // adding a migration should fail this once, so whoever adds it says out loud that the
        // schema changed. A "greater than" check would let a migration appear — or quietly
        // stop being applied — with nothing to show for it.
        assertThat(applied).hasSize(4);
        assertThat(applied.getFirst()).contains("init");
        assertThat(applied.get(1)).contains("device_secret");
        assertThat(applied.get(2)).contains("admin_user");
        assertThat(applied.getLast()).contains("admin_audit");
    }

    @Test
    @DisplayName("the schema Hibernate validated against is the one the migrations built")
    void theTablesAreThere() {
        List<String> tables = db.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                        ORDER BY table_name""")
                .query(String.class).list();

        assertThat(tables).contains("player_profile", "player_progress", "leaderboard");
    }
}
