package com.anatomist.cli;

import com.anatomist.query.EdgeRow;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "callees-of",
        description = "Outgoing CALLS from a method, optionally recursive (--depth N).")
public class CalleesOfCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Method FQN (Class#method or pkg.Class.method).")
    String method;

    @Option(names = "--depth", description = "Recursive depth (1..20). Default 1.")
    int depth = 1;

    @Option(names = "--index") Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<EdgeRow> rows = q.calleesOf(method, depth);
            QueryEnvelope env = new QueryEnvelope("callees-of " + method + " --depth " + depth, rows);
            int maxDepth = rows.stream().mapToInt(r -> r.depth == null ? 0 : r.depth).max().orElse(0);
            env.stats.put("max_depth", maxDepth);
            JsonFormatter.emit(System.out, env);
            return 0;
        }
    }
}
