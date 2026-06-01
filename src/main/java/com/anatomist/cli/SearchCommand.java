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

@Command(name = "search",
        description = "Find nodes by name (FTS5) or by annotation (--by-annotation).")
public class SearchCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Search term (e.g. OrderService, @RestController).")
    String term;

    @Option(names = "--kind", description = "Filter by node kind (CLASS, METHOD, ...).")
    String kind;

    @Option(names = "--limit", description = "Max results. Default 20.")
    int limit = 20;

    @Option(names = "--by-annotation", description = "Treat <term> as an annotation FQN/substring.")
    boolean byAnnotation;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/<repo>/index.db).")
    Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<NodeRow> results = byAnnotation
                    ? q.searchByAnnotation(term, kind, limit)
                    : q.search(term, kind, limit);
            QueryEnvelope env = new QueryEnvelope(buildQueryString(), results);
            JsonFormatter.emit(System.out, env);
            return 0;
        }
    }

    private String buildQueryString() {
        StringBuilder sb = new StringBuilder("search ").append(term);
        if (byAnnotation) sb.append(" --by-annotation");
        if (kind != null) sb.append(" --kind ").append(kind);
        if (limit != 20)  sb.append(" --limit ").append(limit);
        return sb.toString();
    }
}
