package com.anatomist.cli;

import com.anatomist.query.JsonFormatter;
import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "implementors-of",
        description = "Find classes that implement (or extend) the given interface/type.")
public class ImplementorsOfCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Interface or super-type FQN / short label.")
    String type;

    @Option(names = "--index") Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<NodeRow> rows = q.implementorsOf(type);
            QueryEnvelope env = new QueryEnvelope("implementors-of " + type, rows);
            JsonFormatter.emit(System.out, env);
            return 0;
        }
    }
}
