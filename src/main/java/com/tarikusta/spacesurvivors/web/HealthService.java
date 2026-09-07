package com.tarikusta.spacesurvivors.web;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Is the process up, and can it still reach the database? */
@Service
public class HealthService {

    private final JdbcClient db;

    public HealthService(JdbcClient db) {
        this.db = db;
    }

    public Health check() {
        return new Health("UP", databaseReachable() ? "UP" : "DOWN");
    }

    private boolean databaseReachable() {
        try {
            Integer one = db.sql("SELECT 1").query(Integer.class).single();
            return one != null && one == 1;
        } catch (RuntimeException e) {
            // A health check must answer, not throw: "DOWN" is the useful reply here.
            return false;
        }
    }

    public record Health(String status, String db) {
    }
}
