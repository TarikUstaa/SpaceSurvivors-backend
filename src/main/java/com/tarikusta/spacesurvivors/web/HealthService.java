package com.tarikusta.spacesurvivors.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Is the process up, and can it still reach the database? */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final JdbcClient db;

    public HealthService(JdbcClient db) {
        this.db = db;
    }

    public Health check() {
        return new Health("UP", databaseReachable() ? "UP" : "DOWN");
    }

    /**
     * {@code SELECT 1}, and never an exception.
     *
     * <p>A health check must answer: the caller is a probe deciding whether to keep this
     * container in rotation, and a 500 tells it far less than {@code "db":"DOWN"} does.</p>
     *
     * <p><b>Swallowed, but not silent.</b> Returning false without a trace would mean the one
     * moment worth investigating — the database becoming unreachable — leaves nothing behind
     * but a changed string in a response nobody kept. WARN rather than ERROR because the
     * process itself is fine and this may be a blip; the exception carries the reason.</p>
     */
    private boolean databaseReachable() {
        try {
            Integer one = db.sql("SELECT 1").query(Integer.class).single();
            return one != null && one == 1;
        } catch (RuntimeException e) {
            log.warn("health check could not reach the database", e);
            return false;
        }
    }

    public record Health(String status, String db) {
    }
}
