package com.anatomist.cli;

import com.anatomist.query.EnrichResult;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.MarkdownFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "enrich",
        description = "Aggregate node or package view for Agent consumption: "
                    + "members + annotations + semantic annotations + callees + docs + suggested queries.")
public class EnrichCommand implements Callable<Integer> {

    static class Target {
        @Option(names = "--node", description = "FQN or label of a node (class/method) to enrich.")
        String node;
        @Option(names = "--package", description = "Package name to enrich (package-level summary).")
        String pkg;
    }

    @ArgGroup(exclusive = true, multiplicity = "1")
    Target target;

    @Option(names = "--format", description = "Output format: markdown | json (default: markdown).")
    String format = "markdown";

    @Option(names = "--with-docs", description = "Include related documentation snippets (FTS5).")
    boolean withDocs;

    @Option(names = "--depth", description = "Callees depth for --node enrich (default 1).")
    int depth = 1;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/<repo>/index.db).")
    Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            EnrichResult r = target.node != null
                    ? q.enrichNode(target.node, depth, withDocs)
                    : q.enrichPackage(target.pkg, withDocs);
            if (r == null) {
                System.err.println("ERROR: no node or package matches the target.");
                return 2;
            }
            if ("json".equalsIgnoreCase(format)) {
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
        StringBuilder sb = new StringBuilder("enrich");
        if (target.node != null) sb.append(" --node ").append(target.node);
        else sb.append(" --package ").append(target.pkg);
        sb.append(" --format ").append(format);
        if (withDocs) sb.append(" --with-docs");
        if (target.node != null) sb.append(" --depth ").append(depth);
        return sb.toString();
    }
}
