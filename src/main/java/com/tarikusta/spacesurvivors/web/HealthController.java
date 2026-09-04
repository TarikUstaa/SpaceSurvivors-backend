package com.tarikusta.spacesurvivors.web;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /health — is the server up AND can it reach the database?
 *
 * <p>{@code @RestController} = every method returns data (not a view); Spring
 * turns the returned Map into a JSON response body. The {@link JdbcClient} is
 * created by Spring Boot from the datasource in application.properties and
 * injected through the constructor.
 */
@RestController
public class HealthController {

    private final JdbcClient db;

    public HealthController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        // round-trip the DB with the cheapest possible query
        Integer one = db.sql("SELECT 1").query(Integer.class).single();
        return Map.of(
                "status", "UP",
                "db", (one != null && one == 1) ? "UP" : "DOWN"
        );
    }
}
