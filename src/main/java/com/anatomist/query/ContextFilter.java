package com.anatomist.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Post-filters a list of {@link EdgeRow} by the control-flow context recorded
 * on each edge ({@link EdgeRow#context}, produced by
 * {@code extract.ControlContext}).
 *
 * <p>Semantics: filtering is applied uniformly to the returned edge set; the
 * SQL traversal is unchanged. For depth&gt;1 recursive results this filters by
 * each edge's <em>own</em> context — i.e. "the edges in the path that sit
 * inside a loop / branch", not "paths that pass through a loop".</p>
 */
public final class ContextFilter {

    private static final Set<String> LOOP_KINDS = Set.of("for", "foreach", "while", "do");
    private static final Set<String> BRANCH_KINDS = Set.of(
            "if-then", "if-else", "case", "default", "ternary-then", "ternary-else", "catch");

    private ContextFilter() {}

    public static List<EdgeRow> apply(List<EdgeRow> rows, boolean inLoop, boolean inBranch) {
        if (!inLoop && !inBranch) return rows;
        List<EdgeRow> out = new ArrayList<>(rows.size());
        for (EdgeRow r : rows) {
            if (r.context == null) continue;
            if (matches(r.context, inLoop, inBranch)) out.add(r);
        }
        return out;
    }

    private static boolean matches(String context, boolean inLoop, boolean inBranch) {
        for (String seg : context.split(">")) {
            int at = seg.indexOf('@');
            String kind = at >= 0 ? seg.substring(0, at) : seg;
            if (inLoop && LOOP_KINDS.contains(kind)) return true;
            if (inBranch && BRANCH_KINDS.contains(kind)) return true;
        }
        return false;
    }
}
