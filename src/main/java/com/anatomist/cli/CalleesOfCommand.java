package com.anatomist.cli;

import com.anatomist.query.CallChainSlicer;
import com.anatomist.query.ContextFilter;
import com.anatomist.query.EdgeRow;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "callees-of",
        mixinStandardHelpOptions = true,
        description = "Outgoing CALLS from a method, optionally recursive (--depth N).",
        footer = "%nExamples:%n  callees-of com.example.OrderService#create --depth 3"
                + "%n  callees-of OrderService#create --source-window=3"
                + "%n  callees-of OrderService#create --blocks package")
public class CalleesOfCommand extends QueryCommand {

    @Parameters(index = "0", description = "Method FQN (Class#method or pkg.Class.method).")
    String method;

    @Option(names = "--depth", description = "Recursive depth (1..20). Default 1.")
    int depth = 1;

    @Option(names = "--through-callbacks",
            description = "Follow CALLS inside anonymous-class/lambda bodies defined in the method "
                    + "(and nested), attributing them to the method (tagged via=<body>, call_kind=CALLBACK).")
    boolean throughCallbacks;

    @Option(names = "--in-loop", description = "Keep only edges occurring inside a loop (for/foreach/while/do).")
    boolean inLoop;

    @Option(names = "--in-branch", description = "Keep only edges occurring inside a branch (if/else/case/catch/ternary).")
    boolean inBranch;

    @Option(names = "--blocks", arity = "0..1", fallbackValue = "package",
            description = "Slice chain into blocks: class | package (default: package).")
    String blocks;

    @Option(names = "--limit", description = "Max call-chain edges to emit (default 50, 0=all).")
    int limit = 50;

    @Option(names = "--offset", description = "Skip N call-chain edges for pagination.")
    int offset = 0;

    @Option(names = "--filter", description = "Filter edges by source/target/relation substring.")
    String filter;

    @Option(names = "--source-window", arity = "0..1", fallbackValue = "3",
            description = "Attach source_window with path, line, start_line, end_line, "
                    + "and numbered snippet. Optional value is surrounding context lines.")
    Integer sourceWindow;

    @Override
    protected QueryEnvelope execute(QueryService q) {
        List<EdgeRow> rows = ContextFilter.apply(q.calleesOf(method, depth, throughCallbacks), inLoop, inBranch);
        if (filter != null && !filter.isBlank()) {
            rows = rows.stream().filter(e -> Disclosure.matches(e, filter)).toList();
        }
        int total = rows.size();
        List<EdgeRow> page = limit > 0 ? Disclosure.page(rows, limit, offset) : rows;
        if (sourceWindow != null) {
            q.attachSourceWindows(page, Math.max(0, sourceWindow));
        }
        String query = "callees-of " + method + " --depth " + depth
                + (throughCallbacks ? " --through-callbacks" : "");
        QueryEnvelope env = new QueryEnvelope(query, page);
        int maxDepth = rows.stream().mapToInt(r -> r.depth == null ? 0 : r.depth).max().orElse(0);
        env.stats.put("max_depth", maxDepth);
        Disclosure.putPaging(env, total, limit > 0 ? limit : Math.max(total, 1), offset);
        Disclosure.putBudget(env, "edges", page.size(), total);
        if ((Boolean) env.stats.get("truncated")) {
            env.nextQueries = List.of(query + " --limit " + env.stats.get("limit")
                    + " --offset " + env.stats.get("next_offset"));
        }
        if (blocks != null) {
            CallChainSlicer slicer = new CallChainSlicer(q.connection());
            CallChainSlicer.Level level = "class".equalsIgnoreCase(blocks)
                    ? CallChainSlicer.Level.CLASS : CallChainSlicer.Level.PACKAGE;
            env.blocks = slicer.slice(page, level);
        }
        return env;
    }
}
