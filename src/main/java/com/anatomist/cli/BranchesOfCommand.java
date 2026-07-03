package com.anatomist.cli;

import com.anatomist.query.BranchSlice;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "branches-of",
        mixinStandardHelpOptions = true,
        description = "Group branch-contained CALLS/READS/WRITES for a method using indexed control-flow context.",
        footer = "%nExamples:%n  branches-of com.example.OrderService#create --source-window=3"
                + "%n  branches-of OrderService#create --depth 3 --through-callbacks")
public class BranchesOfCommand extends QueryCommand {

    @Parameters(index = "0", description = "Method FQN (Class#method or pkg.Class.method).")
    String method;

    @Option(names = "--depth", description = "Callee expansion depth used to include downstream methods (1..20). Default 1.")
    int depth = 1;

    @Option(names = "--through-callbacks",
            description = "Include methods reached through anonymous-class/lambda callback CALLS.")
    boolean throughCallbacks;

    @Option(names = "--limit", description = "Max branch slices to emit (default 50, 0=all).")
    int limit = 50;

    @Option(names = "--offset", description = "Skip N branch slices for pagination.")
    int offset = 0;

    @Option(names = "--filter", description = "Filter branch slices by owner/context/source/edge labels.")
    String filter;

    @Option(names = "--source-window", arity = "0..1", fallbackValue = "3",
            description = "Attach source_window around the branch line. Optional value is surrounding context lines.")
    Integer sourceWindow;

    @Override
    protected QueryEnvelope execute(QueryService q) {
        String query = "branches-of " + method + " --depth " + depth
                + (throughCallbacks ? " --through-callbacks" : "");
        List<BranchSlice> rows = q.branchesOf(method, depth, throughCallbacks, sourceWindow);
        if (filter != null && !filter.isBlank()) {
            String lower = filter.toLowerCase();
            rows = rows.stream().filter(row -> matches(row, lower)).toList();
        }
        int total = rows.size();
        List<BranchSlice> page = limit > 0 ? Disclosure.page(rows, limit, offset) : rows;
        QueryEnvelope env = new QueryEnvelope(query, page);
        Disclosure.putPaging(env, total, limit > 0 ? limit : Math.max(total, 1), offset);
        Disclosure.putBudget(env, "branch_slices", page.size(), total);
        env.stats.put("max_depth", Math.max(1, Math.min(depth, QueryService.MAX_DEPTH)));
        if ((Boolean) env.stats.get("truncated")) {
            env.nextQueries = List.of(query + " --limit " + env.stats.get("limit")
                    + " --offset " + env.stats.get("next_offset"));
        }
        return env;
    }

    private static boolean matches(BranchSlice row, String lower) {
        if (contains(row.owner, lower) || contains(row.ownerLabel, lower)
                || contains(row.context, lower) || contains(row.sourceFile, lower)) {
            return true;
        }
        return row.calls.stream().anyMatch(edge -> Disclosure.matches(edge, lower))
                || row.reads.stream().anyMatch(edge -> Disclosure.matches(edge, lower))
                || row.writes.stream().anyMatch(edge -> Disclosure.matches(edge, lower));
    }

    private static boolean contains(String value, String lower) {
        return value != null && value.toLowerCase().contains(lower);
    }
}
