package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "search",
        mixinStandardHelpOptions = true,
        description = "Find nodes by name (FTS5), by precise simple-name (--name), or by annotation (--by-annotation).",
        footer = "%nExamples:%n  search OrderService%n  search --name '*EventPlugin' --kind CLASS%n  search Facade --count%n  search @RestController --by-annotation --kind CLASS")
public class SearchCommand extends QueryCommand {

    @Parameters(index = "0", arity = "0..1", description = "Search term (e.g. OrderService, @RestController). Omit when using --name.")
    String term;

    @Option(names = "--kind", description = "Filter by node kind (CLASS, METHOD, ...).")
    String kind;

    @Option(names = "--limit", description = "Max results. Default 20.")
    int limit = 20;

    @Option(names = "--offset", description = "Skip N results for pagination. Default 0.")
    int offset = 0;

    @Option(names = "--name", description = "Precise simple-name match against label (glob: * ?, e.g. '*EventPlugin'). Bypasses FTS.")
    String name;

    @Option(names = "--count", description = "Return only the total count (results omitted), independent of --limit.")
    boolean count;

    @Option(names = "--by-annotation", description = "Treat <term> as an annotation FQN/substring.")
    boolean byAnnotation;

    @Override
    protected QueryEnvelope execute(QueryService q) {
        if (count) {
            int n;
            if (name != null) n = q.countByName(name, kind);
            else n = q.countSearch(term, kind);
            QueryEnvelope env = new QueryEnvelope(buildQueryString(), List.of());
            env.stats.put("total", n);
            env.stats.put("count", n);
            return env;
        }
        List<NodeRow> results;
        int total;
        if (name != null) {
            results = q.searchByName(name, kind, limit, offset);
            total = q.countByName(name, kind);
        } else if (byAnnotation) {
            results = q.searchByAnnotation(term, kind, limit, offset);
            total = q.countByAnnotation(term, kind);
        } else {
            results = q.search(term, kind, limit, offset);
            total = q.countSearch(term, kind);
        }
        QueryEnvelope env = new QueryEnvelope(buildQueryString(), results);
        Disclosure.putPaging(env, total, limit, offset);
        Disclosure.putBudget(env, "rows", results.size(), total);
        if ((Boolean) env.stats.get("truncated")) {
            env.nextQueries = List.of(buildQueryString().replaceAll(" --offset \\d+", "")
                    + " --offset " + env.stats.get("next_offset"));
        }
        // FTS hits can match the package path rather than the class name; surface how many
        // results actually match the simple name so the Agent isn't misled by an inflated total.
        if (name == null && !byAnnotation && term != null) {
            String needle = term.replace("*", "").toLowerCase();
            long labelHits = results.stream()
                    .filter(r -> r.label != null && r.label.toLowerCase().contains(needle))
                    .count();
            env.stats.put("label_matches", labelHits);
        }
        return env;
    }

    private String buildQueryString() {
        StringBuilder sb = new StringBuilder("search");
        if (term != null) sb.append(" ").append(term);
        if (name != null) sb.append(" --name ").append(name);
        if (byAnnotation) sb.append(" --by-annotation");
        if (kind != null) sb.append(" --kind ").append(kind);
        if (count) sb.append(" --count");
        if (limit != 20) sb.append(" --limit ").append(limit);
        if (offset != 0) sb.append(" --offset ").append(offset);
        return sb.toString();
    }
}
