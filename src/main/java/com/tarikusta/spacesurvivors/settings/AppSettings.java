package com.tarikusta.spacesurvivors.settings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * The {@code app_setting} table: a few JSON documents, each replaced whole. See V10__app_setting.sql.
 *
 * <p>Knows nothing about what any setting means — the announcement and the game tunables each
 * validate their own document before it gets here, and parse it on the way out.</p>
 */
@Component
public class AppSettings {

    private final JdbcClient db;
    private final ObjectMapper json;

    public AppSettings(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Optional<JsonNode> get(String key) {
        return db.sql("SELECT value::text FROM app_setting WHERE key = :k")
                .param("k", key)
                .query(String.class)
                .optional()
                .map(json::readTree);
    }

    public void put(String key, JsonNode value, String updatedBy) {
        db.sql("""
                        INSERT INTO app_setting (key, value, updated_by)
                        VALUES (:k, cast(:v as jsonb), :by)
                        ON CONFLICT (key) DO UPDATE
                           SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()""")
                .param("k", key).param("v", json.writeValueAsString(value)).param("by", updatedBy)
                .update();
    }

    /** @return true if there was a value to remove */
    public boolean remove(String key) {
        return db.sql("DELETE FROM app_setting WHERE key = :k").param("k", key).update() > 0;
    }
}
