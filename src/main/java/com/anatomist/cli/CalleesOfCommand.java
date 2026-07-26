package com.anatomist.cli;

import com.anatomist.query.CallChainSlicer;
import com.anatomist.query.ContextFilter;
import com.anatomist.query.EdgeRow;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryCoverageService;
import com.anatomist.query.QueryService;
import com.anatomist.query.TraversalResult;
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

    @Option(names = "--depth", description = "Traversal depth (1..20, default 1); check stats.depth_truncated before treating results as exhaustive.")
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

    @Option(names = "--limit", description = "Max edges emitted after depth traversal (default 50, 0=all); page when stats.truncated=true.")
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
    protected QueryCoverageService.Capability coverageCapability() {
        return QueryCoverageService.Capability.CALL_OUTGOING;
    }

    @Override
    protected List<String> coverageAnchors() {
        return List.of(method);
    }

    @Override
    protected QueryEnvelope execute(QueryService q) {
        TraversalResult<EdgeRow> traversal = q.calleesTraversal(method, depth, throughCallbacks);
        List<EdgeRow> rows = ContextFilter.apply(traversal.items(), inLoop, inBranch);
        if (filter != null && !filter.isBlank()) {
            rows = rows.stream().filter(e -> Disclosure.matches(e, filter)).toList();
        }
        int total = rows.size();
        List<EdgeRow> page = limit > 0 ? Disclosure.page(rows, limit, offset) : rows;
        if (sourceWindow != null) {
            q.attachSourceWindows(page, Math.max(0, sourceWindow));
        }
        List<String> queryArgs = new java.util.ArrayList<>(List.of(
                "callees-of", method, "--depth", String.valueOf(depth)));
        Disclosure.addFlag(queryArgs, throughCallbacks, "--through-callbacks");
        Disclosure.addFlag(queryArgs, inLoop, "--in-loop");
        Disclosure.addFlag(queryArgs, inBranch, "--in-branch");
        Disclosure.addOption(queryArgs, "--filter", filter);
        Disclosure.addOption(queryArgs, "--source-window", sourceWindow);
        Disclosure.addOption(queryArgs, "--blocks", blocks);
        Disclosure.addOption(queryArgs, "--module", module);
        Disclosure.addOption(queryArgs, "--scope", scope);
        String query = Disclosure.renderCommand(queryArgs);
        QueryEnvelope env = new QueryEnvelope(query, page);
        Disclosure.putTraversal(env, traversal, QueryService.MAX_DEPTH);
        Disclosure.putPaging(env, total, limit > 0 ? limit : Math.max(total, 1), offset);
        Disclosure.putBudget(env, "edges", page.size(), total);
        if ((Boolean) env.stats.get("truncated")) {
            List<String> next = new java.util.ArrayList<>(queryArgs);
            Disclosure.addOption(next, "--index", IndexPath.resolve(index));
            Disclosure.addOption(next, "--limit", env.stats.get("limit"));
            Disclosure.addOption(next, "--offset", env.stats.get("next_offset"));
            Disclosure.addNextQuery(env, Disclosure.renderCommand(next));
        }
        Integer nextDepth = Disclosure.nextDepth(traversal, QueryService.MAX_DEPTH);
        if (nextDepth != null) {
            List<String> next = Disclosure.withOption(queryArgs, "--depth", nextDepth);
            Disclosure.addOption(next, "--index", IndexPath.resolve(index));
            Disclosure.addOption(next, "--limit", limit);
            Disclosure.addNextQuery(env, Disclosure.renderCommand(next));
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
