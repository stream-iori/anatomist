package com.anatomist.cli;

import com.anatomist.query.EdgeRow;
import com.anatomist.query.QueryEnvelope;

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

    private static boolean contains(String value, String lower) {
        return value != null && value.toLowerCase().contains(lower);
    }
}
