package com.tarikusta.spacesurvivors.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The thin {@code users} row: identity + "last seen". */
@Service
public class UserService {

    private final JdbcClient db;

    public UserService(JdbcClient db) {
        this.db = db;
    }

    /**
     * Make sure a {@code users} row exists for this id — {@code players} and
     * {@code leaderboard_entries} have a foreign key to it — and bump
     * {@code last_seen_at}. {@code INSERT ... ON CONFLICT DO UPDATE} is Postgres'
     * "upsert": insert if new, otherwise update the existing row.
     */
    public void touch(String userId) {
        String displayName = "Pilot-" + userId.substring(0, Math.min(6, userId.length()));
        db.sql("""
                INSERT INTO users (user_id, display_name)
                VALUES (:id, :name)
                ON CONFLICT (user_id) DO UPDATE SET last_seen_at = now()
                """)
                .param("id", userId)
                .param("name", displayName)
                .update();
    }
}
