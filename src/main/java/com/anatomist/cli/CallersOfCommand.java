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

@Command(name = "callers-of",
        description = "Incoming CALLS to a method (impact analysis), optionally recursive (--depth N).",
        footer = "%nExamples:%n  callers-of com.example.OrderService#create --depth 2%n  callers-of OrderService#create --in-loop")
public class CallersOfCommand extends QueryCommand {

    @Parameters(index = "0", description = "Method FQN (Class#method or pkg.Class.method).")
    String method;

    @Option(names = "--depth", description = "Recursive depth (1..20). Default 1.")
    int depth = 1;

    @Option(names = "--in-loop", description = "Keep only edges occurring inside a loop (for/foreach/while/do).")
    boolean inLoop;

    @Option(names = "--in-branch", description = "Keep only edges occurring inside a branch (if/else/case/catch/ternary).")
    boolean inBranch;

    @Option(names = "--blocks", arity = "0..1", fallbackValue = "package",
            description = "Slice chain into blocks: class | package (default: package).")
    String blocks;

    @Override
    protected QueryEnvelope execute(QueryService q) {
        List<EdgeRow> rows = ContextFilter.apply(q.callersOf(method, depth), inLoop, inBranch);
        QueryEnvelope env = new QueryEnvelope("callers-of " + method + " --depth " + depth, rows);
        int maxDepth = rows.stream().mapToInt(r -> r.depth == null ? 0 : r.depth).max().orElse(0);
        env.stats.put("max_depth", maxDepth);
        if (blocks != null) {
            CallChainSlicer slicer = new CallChainSlicer(q.connection());
            CallChainSlicer.Level level = "class".equalsIgnoreCase(blocks)
                    ? CallChainSlicer.Level.CLASS : CallChainSlicer.Level.PACKAGE;
            env.blocks = slicer.slice(rows, level);
        }
        return env;
    }
}
