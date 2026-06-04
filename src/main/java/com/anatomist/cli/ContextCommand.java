package com.anatomist.cli;

import com.anatomist.query.ContextResult;
import com.anatomist.query.EnrichResult;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.MarkdownFormatter;
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
                    + "Add --with-callees[=N] for outgoing CALLS. "
                    + "Add --enrich for semantic annotations, docs and suggested queries.")
public class ContextCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "FQN or label (Class or Class#method).")
    String target;

    @Option(names = "--enrich",
            description = "Aggregate enriched view: semantic annotations + docs + suggested queries.")
    boolean enrich;

    @Option(names = "--package", description = "Package name (mutually exclusive with positional target).")
    String pkg;

    @Option(names = "--with-callees", arity = "0..1", fallbackValue = "1",
            description = "Include outgoing CALLS, N hops (default 1 when flag present).")
    Integer withCallees;

    @Option(names = "--format", description = "Output format: markdown | json (default: json; with --enrich: markdown).")
    String format;

    @Option(names = "--with-docs", description = "Include related documentation snippets (requires --enrich).")
    boolean withDocs;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/<repo>/index.db).")
    Path index;

    @Override
    public Integer call() {
        if (target != null && pkg != null) {
            System.err.println("ERROR: specify either a positional target or --package, not both.");
            return 2;
        }
        if (target == null && pkg == null) {
            System.err.println("ERROR: specify a target (positional) or --package.");
            return 2;
        }

        Path db = IndexPath.resolve(index);

        if (enrich) {
            return callEnrich(db);
        }
        return callContext(db);
    }

    private int callContext(Path db) {
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

    private int callEnrich(Path db) {
        try (QueryService q = new QueryService(db)) {
            int depth = withCallees != null ? withCallees : 1;
            EnrichResult r = pkg != null
                    ? q.enrichPackage(pkg, withDocs)
                    : q.enrichNode(target, depth, withDocs);
            if (r == null) {
                System.err.println("ERROR: no node or package matches the target.");
                return 2;
            }
            String effectiveFormat = format != null ? format : "markdown";
            if ("json".equalsIgnoreCase(effectiveFormat)) {
                QueryEnvelope env = new QueryEnvelope(buildQueryString(), List.of(r));
                env.stats.clear();
                env.stats.putAll(r.toStats());
                JsonFormatter.emit(System.out, env);
            } else {
                System.out.print(MarkdownFormatter.format(r));
            }
            return 0;
        }
    }

    private String buildQueryString() {
        StringBuilder sb = new StringBuilder("context");
        if (enrich) sb.append(" --enrich");
        if (target != null) sb.append(" ").append(target);
        if (pkg != null) sb.append(" --package ").append(pkg);
        if (withCallees != null) sb.append(" --with-callees=").append(withCallees);
        if (format != null) sb.append(" --format ").append(format);
        if (withDocs) sb.append(" --with-docs");
        return sb.toString();
    }
}
