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

@Command(name = "call-path",
        description = "Shortest CALLS chain from <from> to <to> (BFS; empty when unreachable).")
public class CallPathCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Source method ref.") String from;
    @Parameters(index = "1", description = "Target method ref.") String to;

    @Option(names = "--depth", description = "Max BFS depth. Default 5.")
    int depth = 5;

    @Option(names = "--index") Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<EdgeRow> rows = q.callPath(from, to, depth);
            QueryEnvelope env = new QueryEnvelope(
                    "call-path " + from + " " + to + " --depth " + depth, rows);
            env.stats.put("path_length", rows.size());
            env.stats.put("found", !rows.isEmpty());
            JsonFormatter.emit(System.out, env);
            return rows.isEmpty() ? 2 : 0;
        }
    }
}
