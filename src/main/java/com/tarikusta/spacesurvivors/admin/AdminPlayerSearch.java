package com.tarikusta.spacesurvivors.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The player list, searched and paged.
 *
 * <p><b>SQL through {@link JdbcClient} rather than a repository method</b>, and this is the one
 * query in the backoffice where that is the better tool. The search is optional in three
 * independent ways — a name fragment, an exact id, a filter — and a JPQL query with nullable
 * parameters for each ({@code :id is null or p.playerId = :id}) is the classic way to meet
 * Postgres' "could not determine data type of parameter" at runtime, because an untyped null
 * tells the server nothing. Here each condition is either in the statement or not, and every
 * value still travels as a bound parameter: the only strings joined into the SQL are the fixed
 * fragments written in this file.</p>
 *
 * <p>Replaces an unpaged {@code listAll()} that fetched every player to draw one page.</p>
 */
@Component
public class AdminPlayerSearch {

    public static final int PAGE_SIZE = 50;

    /** The filter above the list. Unknown values fall back to {@link #ALL}. */
    public enum Filter {
        ALL("All players", null),
        REAL("Real players", "NOT p.created_by_admin"),
        TEST("Test players", "p.created_by_admin"),
        SAVED("With a cloud save", "EXISTS (SELECT 1 FROM player_progress g WHERE g.player_id = p.player_id)"),
        UNSAVED("Without a cloud save", "NOT EXISTS (SELECT 1 FROM player_progress g WHERE g.player_id = p.player_id)");

        private final String label;
        private final String condition;

        Filter(String label, String condition) {
            this.label = label;
            this.condition = condition;
        }

        public String label() {
            return label;
        }

        public String param() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Filter parse(String value) {
            String candidate = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            for (Filter filter : values()) {
                if (filter.name().equals(candidate)) {
                    return filter;
                }
            }
            return ALL;
        }
    }

    /**
     * One page of results and enough about the whole result to draw "51–100 of 230" and the links
     * either side of it.
     */
    public record Page(List<AdminPlayerRow> rows, long matching, int page, int size) {

        public boolean hasPrevious() {
            return page > 0;
        }

        public boolean hasNext() {
            return (long) (page + 1) * size < matching;
        }

        public long from() {
            return rows.isEmpty() ? 0 : (long) page * size + 1;
        }

        public long to() {
            return (long) page * size + rows.size();
        }
    }

    private static final String SELECT_ROWS = """
            SELECT p.player_id, p.display_name, p.country, p.first_login_date, p.updated_at,
                   (SELECT count(*) FROM leaderboard l WHERE l.player_id = p.player_id) AS board_entries,
                   (SELECT count(*) FROM player_progress g WHERE g.player_id = p.player_id) AS saves,
                   p.created_by_admin
              FROM player_profile p""";

    private final JdbcClient db;

    public AdminPlayerSearch(JdbcClient db) {
        this.db = db;
    }

    /**
     * @param query  a name fragment (case-insensitive), a two-letter country, or a whole player id
     * @param page   zero-based; a negative value is read as the first page
     */
    public Page search(String query, Filter filter, int page) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();

        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) {
            UUID id = asUuid(q);
            if (id != null) {
                where.add("p.player_id = :id");
                params.put("id", id);
            } else {
                // The fragment is escaped before it becomes a pattern: '_' is legal in a display
                // name and is also LIKE's single-character wildcard, so searching "a_b" would
                // otherwise match "axb" too.
                String nameCondition = "p.display_name ILIKE :pattern ESCAPE '\\'";
                params.put("pattern", "%" + escapeLike(q) + "%");
                if (q.length() == 2) {
                    where.add("(" + nameCondition + " OR p.country = :country)");
                    params.put("country", q.toUpperCase(Locale.ROOT));
                } else {
                    where.add(nameCondition);
                }
            }
        }
        if (filter.condition != null) {
            where.add(filter.condition);
        }

        String whereClause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        int current = Math.max(page, 0);

        JdbcClient.StatementSpec count = db.sql("SELECT count(*) FROM player_profile p" + whereClause);
        params.forEach(count::param);
        long matching = count.query(Long.class).single();

        // Joined with explicit spaces: a text block strips its own leading indentation, so
        // "p" + "" + "ORDER BY" would otherwise arrive as "pORDER BY" whenever there is no WHERE.
        JdbcClient.StatementSpec select = db.sql(SELECT_ROWS + whereClause
                + " ORDER BY p.first_login_date DESC, p.player_id LIMIT :limit OFFSET :offset");
        params.forEach(select::param);
        List<AdminPlayerRow> rows = select
                .param("limit", PAGE_SIZE)
                .param("offset", (long) current * PAGE_SIZE)
                .query((rs, n) -> row(rs))
                .list();

        return new Page(rows, matching, current, PAGE_SIZE);
    }

    private static AdminPlayerRow row(ResultSet rs) throws SQLException {
        return new AdminPlayerRow(
                rs.getObject("player_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("country"),
                instant(rs, "first_login_date"),
                instant(rs, "updated_at"),
                rs.getLong("board_entries"),
                rs.getLong("saves"),
                rs.getBoolean("created_by_admin"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    private static UUID asUuid(String text) {
        try {
            return text.length() == 36 ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
