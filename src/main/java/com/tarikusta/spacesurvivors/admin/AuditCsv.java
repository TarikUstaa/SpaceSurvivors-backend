package com.tarikusta.spacesurvivors.admin;

import java.util.List;

/**
 * Turns audit rows into CSV that is safe to open in a spreadsheet.
 *
 * <p><b>Two things a naive join gets wrong.</b> A summary contains commas and quotes, so every
 * field is quoted and inner quotes doubled (RFC 4180). And a cell that begins with {@code =},
 * {@code +}, {@code -} or {@code @} is read by Excel and LibreOffice as a formula — the
 * <em>actor</em> column of a refused sign-in is whatever a stranger typed into the login form, so
 * {@code =HYPERLINK("http://evil",…)} would arrive as a live formula in the administrator's
 * spreadsheet. Such a cell is prefixed with an apostrophe, which spreadsheets treat as "this is
 * text" and do not display.</p>
 */
final class AuditCsv {

    private AuditCsv() {
    }

    static String write(List<AdminAuditSearch.Row> rows, boolean truncated) {
        StringBuilder out = new StringBuilder();
        line(out, "audit_id", "happened_at_utc", "actor", "action", "target", "summary", "actor_ip");
        for (AdminAuditSearch.Row row : rows) {
            line(out, String.valueOf(row.auditId()), row.when(), row.actor(),
                    row.action() == null ? "" : row.action().name(),
                    row.target(), row.summary(), row.actorIp());
        }
        if (truncated) {
            line(out, "", "", "", "", "", "export cut at " + rows.size() + " rows — narrow the filter", "");
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells[i]));
        }
        out.append("\r\n");
    }

    static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
