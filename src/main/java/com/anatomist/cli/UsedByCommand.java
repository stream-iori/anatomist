package com.anatomist.cli;

import com.anatomist.query.ContextFilter;
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

@Command(name = "used-by",
        description = "Incoming CALLS + REFERENCES to a type (and its methods) — impact analysis.")
public class UsedByCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Type FQN or short label.")
    String type;

    @Option(names = "--index") Path index;

    @Option(names = "--in-loop", description = "Keep only edges occurring inside a loop (for/foreach/while/do).")
    boolean inLoop;

    @Option(names = "--in-branch", description = "Keep only edges occurring inside a branch (if/else/case/catch/ternary).")
    boolean inBranch;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<EdgeRow> rows = ContextFilter.apply(q.usedBy(type), inLoop, inBranch);
            QueryEnvelope env = new QueryEnvelope("used-by " + type, rows);
            JsonFormatter.emit(System.out, env);
            return 0;
        }
    }
}
