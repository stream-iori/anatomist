package com.anatomist.cli;

import com.anatomist.query.EdgeRow;
import com.anatomist.query.ContextFilter;
import com.anatomist.query.PagedResult;
import com.anatomist.query.QueryEnvelope;

import java.util.ArrayList;
import java.util.List;

final class Disclosure {

    private Disclosure() {}

    static <T> List<T> page(List<T> rows, int limit, int offset) {
        int total = rows.size();
        int safeOffset = Math.max(0, Math.min(offset, total));
        int safeLimit = limit > 0 ? limit : 50;
        int end = Math.min(safeOffset + safeLimit, total);
        return rows.subList(safeOffset, end);
    }

    static void putPaging(QueryEnvelope env, int total, int limit, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, total));
        int safeLimit = limit > 0 ? limit : 50;
        int next = safeOffset + safeLimit;
        boolean truncated = next < total;
        env.stats.put("total", total);
        env.stats.put("offset", safeOffset);
        env.stats.put("limit", safeLimit);
        env.stats.put("truncated", truncated);
        if (truncated) env.stats.put("next_offset", next);
    }

    static void putBudget(QueryEnvelope env, String mode, int emitted, int total) {
        env.budget.put("mode", mode);
        env.budget.put("emitted", emitted);
        env.budget.put("total", total);
        env.budget.put("truncated", emitted < total);
    }

    static boolean matches(EdgeRow e, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String f = filter.toLowerCase();
        return contains(e.sourceLabel, f)
                || contains(e.source, f)
                || contains(e.targetLabel, f)
                || contains(e.target, f)
                || contains(e.targetQualifiedName, f)
                || contains(e.externalTargetFqn, f)
                || contains(e.relation, f)
                || contains(e.callKind, f);
    }

    static PagedResult<EdgeRow> filterAndPage(List<EdgeRow> rows,
                                               boolean inLoop,
                                               boolean inBranch,
                                               String filter,
                                               int limit,
                                               int offset) {
        List<EdgeRow> filtered = ContextFilter.apply(rows, inLoop, inBranch);
        if (filter != null && !filter.isBlank()) {
            filtered = filtered.stream().filter(e -> matches(e, filter)).toList();
        }
        int total = filtered.size();
        int safeOffset = Math.max(0, Math.min(offset, total));
        int safeLimit = limit > 0 ? limit : Math.max(total, 1);
        int end = Math.min(safeOffset + safeLimit, total);
        return new PagedResult<>(new ArrayList<>(filtered.subList(safeOffset, end)),
                total, end < total, safeOffset);
    }

    static String renderCommand(List<String> args) {
        return args.stream().map(Disclosure::shellQuote).reduce((a, b) -> a + " " + b).orElse("");
    }

    static void addFlag(List<String> args, boolean enabled, String flag) {
        if (enabled) args.add(flag);
    }

    static void addOption(List<String> args, String name, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        args.add(name);
        args.add(String.valueOf(value));
    }

    private static String shellQuote(String value) {
        if (value.matches("[A-Za-z0-9_./:=@+,-]+")) return value;
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean contains(String value, String lower) {
        return value != null && value.toLowerCase().contains(lower);
    }
}
