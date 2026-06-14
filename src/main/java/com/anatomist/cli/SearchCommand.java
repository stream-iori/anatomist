package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "search",
        description = "Find nodes by name (FTS5), by annotation (--by-annotation), or by arch role (--by-role).",
        footer = "%nExamples:%n  search OrderService%n  search @RestController --by-annotation --kind CLASS%n  search ADAPTER --by-role")
public class SearchCommand extends QueryCommand {

    @Parameters(index = "0", description = "Search term (e.g. OrderService, @RestController).")
    String term;

    @Option(names = "--kind", description = "Filter by node kind (CLASS, METHOD, ...).")
    String kind;

    @Option(names = "--limit", description = "Max results. Default 20.")
    int limit = 20;

    @Option(names = "--by-annotation", description = "Treat <term> as an annotation FQN/substring.")
    boolean byAnnotation;

    @Option(names = "--by-role", description = "Find nodes by arch_role (ENTRY, APPLICATION, DOMAIN_SERVICE, DOMAIN_MODEL, REPOSITORY, ADAPTER, INFRASTRUCTURE).")
    boolean byRole;

    @Override
    protected QueryEnvelope execute(QueryService q) {
        List<NodeRow> results;
        if (byRole) results = q.searchByRole(term, limit);
        else if (byAnnotation) results = q.searchByAnnotation(term, kind, limit);
        else results = q.search(term, kind, limit);
        return new QueryEnvelope(buildQueryString(), results);
    }

    private String buildQueryString() {
        StringBuilder sb = new StringBuilder("search ").append(term);
        if (byAnnotation) sb.append(" --by-annotation");
        if (kind != null) sb.append(" --kind ").append(kind);
        if (limit != 20) sb.append(" --limit ").append(limit);
        return sb.toString();
    }
}
