package com.anatomist.query;

import java.sql.*;
import java.util.*;

/** Navigation candidates from persisted ranges; does not infer Java semantics. */
final class DiffNavigation {
    record Declaration(String id, String symbol, String kind, String module, String scope,
                       String file, int start, int startColumn, int end, int endColumn, boolean synthetic) {
        boolean located() { return !synthetic && start > 0 && end >= start; }
        boolean callable() { return kind.equals("METHOD") || kind.equals("CONSTRUCTOR"); }
        boolean type() { return Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD").contains(kind); }
        boolean selected(String selectedScope, String selectedModule) {
            return (selectedScope.equals("ALL") || scope.equals(selectedScope))
                    && (selectedModule == null || module.equals(selectedModule));
        }
        Map<String,Object> anchor(String snapshot, String precision) {
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("snapshot_id", snapshot); out.put("id", id); out.put("symbol", symbol);
            out.put("kind", kind); out.put("module", module); out.put("scope", scope); out.put("file", file);
            out.put("origin", synthetic ? "derived" : "source"); out.put("precision", precision);
            if (located()) {
                out.put("start_line", start); out.put("end_line", end);
                if (startColumn > 0) out.put("start_column", startColumn);
                if (endColumn > 0) out.put("end_column", endColumn);
            }
            return out;
        }
    }

    static Map<String,Declaration> read(Connection connection) throws SQLException {
        Map<String,Declaration> out = new TreeMap<>();
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery(
                "SELECT *,coalesce(declaration_begin_line,begin_line) bl,coalesce(declaration_end_line,end_line) el,"
                + "coalesce(declaration_begin_column,begin_column) bc,coalesce(declaration_end_column,end_column) ec "
                + "FROM nodes WHERE declaration_kind IS NOT NULL OR kind='FIELD'")) {
            while (r.next()) {
                var d = new Declaration(r.getString("id"), r.getString("symbol_id"), r.getString("kind"),
                        r.getString("module"), r.getString("scope"), r.getString("source_file"),
                        r.getInt("bl"), r.getInt("bc"), r.getInt("el"), r.getInt("ec"), r.getBoolean("synthetic"));
                out.put(d.id(), d);
            }
        }
        return out;
    }

    static Map<String,String> locate(Collection<Declaration> declarations, String file,
                                    List<DiffTextChanges.Lines> ranges) {
        List<Declaration> located = declarations.stream().filter(d -> d.file().equals(file) && d.located()).toList();
        Map<String,String> hits = new TreeMap<>();
        // Visit interval boundaries rather than every line of a large file.
        for (var range : ranges) {
            if (range.count() == 0) continue; // A zero-length side contains no changed source.
            TreeSet<Integer> points = new TreeSet<>(); points.add(range.start());
            for (var d : located) {
                if (range.contains(d.start())) points.add(d.start());
                if (d.end() < Integer.MAX_VALUE && range.contains(d.end() + 1)) points.add(d.end() + 1);
            }
            for (int point : points) {
                var candidates = located.stream().filter(d -> d.start() <= point && d.end() >= point).toList();
                var inner = candidates.stream().filter(d -> candidates.stream().noneMatch(other ->
                        !d.id().equals(other.id()) && encloses(d, other))).toList();
                for (var d : inner) {
                    String precision = inner.size() > 1 ? "candidate" : d.type() ? "owner" : "declaration";
                    hits.merge(d.id(), precision, (a,b) -> a.equals("candidate") ? a : b);
                }
            }
        }
        return hits;
    }

    private static boolean encloses(Declaration outer, Declaration inner) {
        long a = ((long) outer.start() << 32) + outer.startColumn();
        long b = ((long) outer.end() << 32) + outer.endColumn();
        long c = ((long) inner.start() << 32) + inner.startColumn();
        long d = ((long) inner.end() << 32) + inner.endColumn();
        return a <= c && b >= d && (a < c || b > d);
    }
}
