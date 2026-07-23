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
        description = "Find project nodes plus virtual EXTERNAL_CLASS targets already referenced by this project; --name and --by-annotation remain supported.",
        footer = "%nExamples:%n  search OrderService%n  search SafeFastjsonParser%n  search --kind EXTERNAL_CLASS SafeFastjsonParser%n  search --name '*Plugin' --kind CLASS%n  search Facade --count%n  search @Deprecated --by-annotation")
public class SearchCommand extends QueryCommand {

    @Parameters(index = "0", arity = "0..1", description = "Search term (e.g. OrderService, @Deprecated). Omit when using --name.")
    String term;

    @Option(names = "--kind", description = "Filter by node kind (CLASS, METHOD, ..., EXTERNAL_CLASS for virtual classpath targets).")
    String kind;

    @Option(names = "--limit", description = "Max results. Default 20.")
    int limit = 20;

    @Option(names = "--offset", description = "Skip N results for pagination. Default 0.")
    int offset = 0;

    @Option(names = "--name", description = "Precise simple-name match against label (glob: * ?, e.g. '*Plugin'); includes virtual external types unless another kind is selected.")
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
            int nextOffset = ((Number) env.stats.get("next_offset")).intValue();
            env.nextQueries = List.of(buildQueryString(nextOffset, true) + " "
                    + Disclosure.renderCommand(List.of("--index", IndexPath.resolve(index).toString())));
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
        return buildQueryString(offset, false);
    }

    private String buildQueryString(int effectiveOffset, boolean offsetLast) {
        List<String> args = new java.util.ArrayList<>();
        args.add("search");
        if (term != null) args.add(term);
        if (name != null) {
            args.add("--name");
            args.add(name);
        }
        Disclosure.addFlag(args, byAnnotation, "--by-annotation");
        if (kind != null) {
            args.add("--kind");
            args.add(kind);
        }
        Disclosure.addFlag(args, count, "--count");
        if (limit != 20) Disclosure.addOption(args, "--limit", limit);
        if (effectiveOffset != 0 && !offsetLast) {
            Disclosure.addOption(args, "--offset", effectiveOffset);
        }
        Disclosure.addOption(args, "--module", module);
        Disclosure.addOption(args, "--scope", scope);
        if (effectiveOffset != 0 && offsetLast) {
            Disclosure.addOption(args, "--offset", effectiveOffset);
        }
        return String.join(" ", args);
    }
}
