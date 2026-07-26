package com.anatomist.cli;

import com.anatomist.query.EdgeRow;
import com.anatomist.query.ContextFilter;
import com.anatomist.query.PagedResult;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.TraversalResult;

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

    static void putTraversal(QueryEnvelope env, TraversalResult<?> traversal, int maximumDepth) {
        env.stats.put("depth_requested", traversal.requestedDepth());
        env.stats.put("depth_effective", traversal.effectiveDepth());
        env.stats.put("max_depth", traversal.reachedDepth());
        env.stats.put("depth_truncated", traversal.depthTruncated());
        env.stats.put("frontier_count", traversal.frontierCount());
        env.stats.put("depth_limit_reached",
                traversal.depthTruncated() && traversal.effectiveDepth() >= maximumDepth);
        if (traversal.limitTruncated()) env.stats.put("limit_truncated", true);
    }

    static Integer nextDepth(TraversalResult<?> traversal, int maximumDepth) {
        if (!traversal.depthTruncated() || traversal.effectiveDepth() >= maximumDepth) return null;
        return Math.min(maximumDepth,
                Math.max(traversal.effectiveDepth() + 1, traversal.effectiveDepth() * 2));
    }

    static Integer nextDepth(QueryEnvelope env, int maximumDepth) {
        if (!Boolean.TRUE.equals(env.stats.get("depth_truncated"))) return null;
        int effective = number(env.stats.get("depth_effective"));
        if (effective >= maximumDepth) return null;
        return Math.min(maximumDepth, Math.max(effective + 1, effective * 2));
    }

    static void addNextQuery(QueryEnvelope env, String query) {
        if (query == null || query.isBlank()) return;
        List<String> next = env.nextQueries == null
                ? new ArrayList<>() : new ArrayList<>(env.nextQueries);
        if (!next.contains(query)) next.add(query);
        env.nextQueries = List.copyOf(next);
    }

    static List<String> withOption(List<String> args, String name, Object value) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (name.equals(args.get(i))) {
                if (i + 1 < args.size()) i++;
                continue;
            }
            out.add(args.get(i));
        }
        addOption(out, name, value);
        return out;
    }

    static void applyBoundedEvidence(QueryEnvelope env, boolean limitIsTraversalBudget) {
        boolean depth = Boolean.TRUE.equals(env.stats.get("depth_truncated"));
        boolean truncated = Boolean.TRUE.equals(env.stats.get("truncated"));
        boolean page = !limitIsTraversalBudget && (truncated
                || number(env.stats.get("offset")) > 0);
        boolean limit = limitIsTraversalBudget && truncated;
        if (!depth && !page && !limit) return;

        env.evidence.put("negative_conclusion_safe", false);
        List<String> dimensions = new ArrayList<>();
        Object existing = env.evidence.get("affected_dimensions");
        if (existing instanceof List<?> values) {
            values.stream().map(String::valueOf).forEach(dimensions::add);
        }
        if (page && !dimensions.contains("query_page")) dimensions.add("query_page");
        if (depth && !dimensions.contains("query_depth")) dimensions.add("query_depth");
        if (limit && !dimensions.contains("query_limit")) dimensions.add("query_limit");
        env.evidence.put("affected_dimensions", dimensions.stream().sorted().toList());

        if ("confirmed_empty".equals(env.evidence.get("status"))) {
            env.evidence.put("status", "indeterminate");
            env.evidence.put("code", depth ? "QUERY_DEPTH_TRUNCATED"
                    : limit ? "QUERY_LIMIT_TRUNCATED" : "QUERY_PAGE_INCOMPLETE");
        }
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
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
        if (isShellSafe(value)) return value;
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean isShellSafe(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!(character >= 'A' && character <= 'Z')
                    && !(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && "_./:=@+,-".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(String value, String lower) {
        return value != null && value.toLowerCase().contains(lower);
    }
}
