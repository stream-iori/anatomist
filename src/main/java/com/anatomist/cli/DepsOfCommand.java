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

@Command(name = "deps-of",
        description = "Outgoing CALLS + REFERENCES from a type (and its methods).")
public class DepsOfCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Type FQN or short label.")
    String type;

    @Option(names = "--index") Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<EdgeRow> rows = q.depsOf(type);
            QueryEnvelope env = new QueryEnvelope("deps-of " + type, rows);
            JsonFormatter.emit(System.out, env);
            return 0;
        }
    }
}
