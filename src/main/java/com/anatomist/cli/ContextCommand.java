package com.anatomist.cli;

import com.anatomist.query.ContextResult;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "context",
        description = "Show node + contained members + annotations. "
                    + "Add --with-callees[=N] for outgoing CALLS.")
public class ContextCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "FQN or label (Class or Class#method).")
    String target;

    @Option(names = "--with-callees", arity = "0..1", fallbackValue = "1",
            description = "Include outgoing CALLS, N hops (default 1 when flag present).")
    Integer withCallees;

    @Option(names = "--index", description = "Path to index.db (default: ./.anatomist/index.db).")
    Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            ContextResult r = q.context(target, withCallees == null ? 0 : withCallees);
            QueryEnvelope env = new QueryEnvelope(buildQueryString(),
                    r == null ? List.of() : List.of(r));
            env.stats.clear();
            if (r != null) env.stats.putAll(r.toStats());
            else env.stats.put("total", 0);
            JsonFormatter.emit(System.out, env);
            return r == null ? 2 : 0;
        }
    }

    private String buildQueryString() {
        StringBuilder sb = new StringBuilder("context ").append(target);
        if (withCallees != null) sb.append(" --with-callees=").append(withCallees);
        return sb.toString();
    }
}
