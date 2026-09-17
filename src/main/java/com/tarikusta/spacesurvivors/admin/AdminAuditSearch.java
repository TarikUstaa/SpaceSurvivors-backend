package com.tarikusta.spacesurvivors.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading the audit trail through a filter.
 *
 * <p><b>Separate from {@link AdminAuditRepository} on purpose.</b> That interface is deliberately
 * three methods long, and a test holds it there — its narrowness is how "append-only" is enforced.
 * Adding filtered reads to it would put the next method one line from a {@code delete}. This class
 * can only read: it holds a {@link JdbcClient} and every statement in it is a {@code SELECT}.</p>
 *
 * <p>Same construction as {@link AdminPlayerSearch}: fixed SQL fragments, bound values.</p>
 */
@Component
public class AdminAuditSearch {

    /** What the page draws. The whole matching count is still reported. */
    public static final int PAGE_LIMIT = 200;

    /** What an export may contain. Past this the file says it was cut, and so does its audit row. */
    public static final int EXPORT_LIMIT = 10_000;

    private static final DateTimeFormatter SECOND =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).withLocale(Locale.ROOT);

    /**
     * The filter as the person typed it, with anything unusable already dropped — so the form can
     * be redrawn showing exactly what was applied, not what was asked for.
     *
     * @param from inclusive, a UTC calendar day
     * @param to   inclusive, a UTC calendar day
     */
    public record Filter(String actor, AdminAction action, String text, LocalDate from, LocalDate to) {

        public static Filter of(String actor, String action, String text, String from, String to) {
            return new Filter(blankToNull(actor), parseAction(action), blankToNull(text),
                    parseDay(from), parseDay(to));
        }

        public boolean isEmpty() {
            return actor == null && action == null && text == null && from == null && to == null;
        }

        /** For the audit row an export writes: what was exported, in words. */
        public String describe() {
            if (isEmpty()) {
                return "everything";
            }
            List<String> parts = new ArrayList<>();
            if (actor != null) parts.add("actor '" + actor + "'");
            if (action != null) parts.add("action " + action.name());
            if (text != null) parts.add("text '" + text + "'");
            if (from != null) parts.add("from " + from);
            if (to != null) parts.add("to " + to);
            return String.join(", ", parts);
        }

        public String actionName() {
            return action == null ? "" : action.name();
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        private static AdminAction parseAction(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return AdminAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }

        private static LocalDate parseDay(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(value.trim());
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }

    /** One line of the trail, as read — the same shape the page has always drawn. */
    public record Row(long auditId, Instant happenedAt, String actor, AdminAction action,
                      String target, String summary, String actorIp) {

        public String when() {
            return happenedAt == null ? "—" : SECOND.format(happenedAt);
        }

        public String label() {
            return action == null ? "" : action.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    public record Result(List<Row> rows, long matching, int limit) {

        public boolean truncated() {
            return matching > rows.size();
        }
    }

    private final JdbcClient db;

    public AdminAuditSearch(JdbcClient db) {
        this.db = db;
    }

    public Result search(Filter filter, int limit) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();

        if (filter.actor() != null) {
            where.add("lower(actor) LIKE :actor ESCAPE '\\'");
            params.put("actor", "%" + escapeLike(filter.actor().toLowerCase(Locale.ROOT)) + "%");
        }
        if (filter.action() != null) {
            where.add("action = :action");
            params.put("action", filter.action().name());
        }
        if (filter.text() != null) {
            where.add("(summary ILIKE :text ESCAPE '\\' OR target ILIKE :text ESCAPE '\\')");
            params.put("text", "%" + escapeLike(filter.text()) + "%");
        }
        if (filter.from() != null) {
            where.add("happened_at >= :from");
            params.put("from", filter.from().atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (filter.to() != null) {
            // Inclusive of the whole day: everything before the start of the next one.
            where.add("happened_at < :to");
            params.put("to", filter.to().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        }

        String whereClause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);

        JdbcClient.StatementSpec count = db.sql("SELECT count(*) FROM admin_audit" + whereClause);
        params.forEach(count::param);
        long matching = count.query(Long.class).single();

        JdbcClient.StatementSpec select = db.sql(
                "SELECT audit_id, happened_at, actor, action, target, summary, actor_ip FROM admin_audit"
                + whereClause + " ORDER BY happened_at DESC, audit_id DESC LIMIT :limit");
        params.forEach(select::param);
        List<Row> rows = select.param("limit", limit).query((rs, n) -> row(rs)).list();

        return new Result(rows, matching, limit);
    }

    private static Row row(ResultSet rs) throws SQLException {
        OffsetDateTime at = rs.getObject("happened_at", OffsetDateTime.class);
        AdminAction action;
        try {
            action = AdminAction.valueOf(rs.getString("action"));
        } catch (IllegalArgumentException retired) {
            // A row written by a version that had an action this one has since dropped. Shown
            // without a label rather than failing the whole page over one old line.
            action = null;
        }
        return new Row(rs.getLong("audit_id"), at == null ? null : at.toInstant(),
                rs.getString("actor"), action, rs.getString("target"), rs.getString("summary"),
                rs.getString("actor_ip"));
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
