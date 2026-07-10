package com.anatomist.cli;

import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

public abstract class QueryCommand implements Callable<Integer> {

    @Option(names = "--index") Path index;
    @Option(names = "--module", description = "Restrict symbol resolution to one module.") String module;
    @Option(names = "--scope", description = "Source scope: MAIN | TEST | GENERATED | ALL (default MAIN).",
            defaultValue = "MAIN") String scope;

    @Override
    public final Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            q.selectNodes(module, scope);
            QueryEnvelope env = execute(q);
            JsonFormatter.emit(System.out, env);
            return 0;
        }
    }

    protected abstract QueryEnvelope execute(QueryService q);
}
