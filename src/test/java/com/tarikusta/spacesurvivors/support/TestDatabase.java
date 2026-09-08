package com.tarikusta.spacesurvivors.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real Postgres, started for the tests and thrown away after them.
 *
 * <p><b>Why not simply point the tests at the developer's own database.</b> That is what
 * they used to do, and it meant the suite only passed on a machine somebody had set up by
 * hand: the right Postgres, the right role, and a git-ignored properties file holding its
 * password. A fresh clone could not run the tests at all, which also means no continuous
 * integration could. It was also shared state — a test that left a row behind could change
 * the result of the next run, and the database still held whatever the last manual poke at
 * the running server had done to it.</p>
 *
 * <p>This container starts empty every time, so Flyway applies V1 and V2 from nothing on
 * each run. That is worth having on its own: it means the migrations are exercised as a
 * new deployment would experience them, not just as an already-migrated database.</p>
 *
 * <p><b>{@link ServiceConnection} is what removes the configuration.</b> Without it we
 * would have to read the container's generated port and password back out and feed them to
 * {@code spring.datasource.*} by hand. With it, Spring Boot takes the connection details
 * from the container bean directly and they always agree — there is no copy to drift.</p>
 *
 * <p>The image tag is pinned to the major version running in production rather than left
 * at whatever the library defaults to. A test database on a different major version can
 * accept SQL the real one rejects, which turns the suite into a source of false
 * confidence.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

    /** Matches the local and intended production server: Postgres 18. */
    private static final String IMAGE = "postgres:18";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE);
    }
}
