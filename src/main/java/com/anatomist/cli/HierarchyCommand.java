package com.anatomist.cli;

import com.anatomist.query.HierarchyResult;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "hierarchy",
        description = "Show extends chain + direct implements for a type.")
public class HierarchyCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Type FQN or short label.")
    String type;

    @Option(names = "--index") Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            HierarchyResult r = q.hierarchy(type);
            QueryEnvelope env = new QueryEnvelope("hierarchy " + type, List.of(r));
            env.stats.clear();
            env.stats.putAll(r.toStats());
            JsonFormatter.emit(System.out, env);
            return 0;
        }
    }
}
