package com.anatomist.cli;

import com.anatomist.query.CallChainSlicer;
import com.anatomist.query.EdgeRow;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryCoverageService;
import com.anatomist.query.QueryService;
import com.anatomist.query.TraversalResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "call-path",
        mixinStandardHelpOptions = true,
        description = "Shortest CALLS chain from <from> to <to> (BFS; empty when unreachable).",
        footer = "%nExamples:%n  call-path Controller#handle Repository#save --depth 5"
                + "%n  call-path Controller#handle Repository#save --source-window=2")
public class CallPathCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Source method ref.") String from;
    @Parameters(index = "1", description = "Target method ref.") String to;

    @Option(names = "--depth", description = "Max BFS traversal depth (1..20, default 5); an empty path may be depth-truncated.")
    int depth = 5;

    @Option(names = "--index") Path index;

    @Option(names = "--blocks", arity = "0..1", fallbackValue = "package",
            description = "Slice chain into blocks: class | package (default: package).")
    String blocks;

    @Option(names = "--through-callbacks",
            description = "Follow calls inside anonymous-class / lambda callback bodies and record via=<body-id>.")
    boolean throughCallbacks;

    @Option(names = "--source-window", arity = "0..1", fallbackValue = "3",
            description = "Attach source_window with path, line, start_line, end_line, "
                    + "and numbered snippet. Optional value is surrounding context lines.")
    Integer sourceWindow;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            TraversalResult<EdgeRow> traversal = q.callPathTraversal(
                    from, to, depth, throughCallbacks);
            List<EdgeRow> rows = traversal.items();
            if (sourceWindow != null) {
                q.attachSourceWindows(rows, Math.max(0, sourceWindow));
            }
            List<String> queryArgs = new java.util.ArrayList<>(List.of(
                    "call-path", from, to, "--depth", String.valueOf(depth)));
            Disclosure.addFlag(queryArgs, throughCallbacks, "--through-callbacks");
            Disclosure.addOption(queryArgs, "--source-window", sourceWindow);
            Disclosure.addOption(queryArgs, "--blocks", blocks);
            QueryEnvelope env = new QueryEnvelope(Disclosure.renderCommand(queryArgs), rows);
            env.stats.put("path_length", rows.size());
            env.stats.put("found", !rows.isEmpty());
            Disclosure.putTraversal(env, traversal, QueryService.MAX_DEPTH);
            Integer nextDepth = Disclosure.nextDepth(traversal, QueryService.MAX_DEPTH);
            if (nextDepth != null) {
                List<String> next = Disclosure.withOption(queryArgs, "--depth", nextDepth);
                Disclosure.addOption(next, "--index", db);
                Disclosure.addNextQuery(env, Disclosure.renderCommand(next));
            }
            if (blocks != null) {
                CallChainSlicer slicer = new CallChainSlicer(q.connection());
                CallChainSlicer.Level level = "class".equalsIgnoreCase(blocks)
                        ? CallChainSlicer.Level.CLASS : CallChainSlicer.Level.PACKAGE;
                env.blocks = slicer.slice(rows, level);
            }
            env.evidence.putAll(new QueryCoverageService(q.connection()).assess(
                    QueryCoverageService.Capability.CALL_PATH,
                    List.of(from, to), null, "MAIN", !rows.isEmpty(), false).toMap());
            Disclosure.applyBoundedEvidence(env, false);
            JsonFormatter.emit(System.out, env);
            return rows.isEmpty() ? 2 : 0;
        }
    }
}
