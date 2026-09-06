package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryService;
import com.anatomist.query.SearchService;
import com.anatomist.query.semantic.SemanticCursor;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.query.semantic.SemanticRecords;
import com.anatomist.query.semantic.SemanticStreamWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Locale;

@Command(name = "search",
        mixinStandardHelpOptions = true,
        description = "Find indexed entities using the semantic-stream/v1 contract.",
        footer = "%nAccepts: CLI selector%nEmits: entity_candidate | result_count + evidence%nOperation: search; inspect with: anatomist operations search%n%nExamples:%n  search OrderService%n  search PaymentGateway --kind type --format ndjson%n  search --name '*Plugin' --kind type%n  search Facade --count%n  search @Deprecated --by-annotation")
public class SearchCommand extends SemanticCommand {

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

    @Option(names = "--include-meta", description = "With --by-annotation, include cycle-safe meta-annotation matches (max depth 16).")
    boolean includeMeta;

    @Override
    protected String directSeed() {
        String selector = name != null ? name : term;
        return selector == null || selector.isBlank() ? null
                : SemanticRecords.rootSeed("search", selector);
    }

    @Override
    protected Result execute(QueryService q, SemanticIdentity identity,
                             SemanticStreamWriter writer) {
        validateSemanticOptions();
        String selector = name != null ? name : term;
        String seed = SemanticRecords.rootSeed("search", selector);
        int emitted = 0;
        boolean truncated = false;
        SearchService.SemanticMode mode = name != null ? SearchService.SemanticMode.NAME
                : byAnnotation ? SearchService.SemanticMode.ANNOTATION
                : SearchService.SemanticMode.FTS;
        if (count) {
            int total = 0;
            try (SemanticCursor<NodeRow> rows = q.semanticSearchCursor(
                    mode, selector, kind, SemanticRecords.MAX_LIMIT, 0, includeMeta)) {
                while (rows.hasNext()) { rows.next(); total++; }
            }
            var result = SemanticRecords.common("result_count", seed, null, identity);
            result.put("id", "count:sha256:" + SemanticIdentity.sha256(
                    mode + "\n" + selector + "\n" + String.valueOf(kind)));
            result.put("operation", "search");
            result.put("selector", selector);
            if (kind != null) result.put("kind", kind);
            result.put("count", total);
            result.put("origin", "derived");
            result.put("resolution_status", "exact");
            writer.write(result);
            writer.write(SemanticRecords.seedEvidence(seed, null, 1, true, null, identity));
            return new Result(1, 1, true, false);
        }
        try (SemanticCursor<NodeRow> rows = q.semanticSearchCursor(
                mode, selector, kind, limit + 1, offset, includeMeta)) {
            while (rows.hasNext()) {
                NodeRow row = rows.next();
                if (emitted >= limit) {
                    truncated = true;
                    break;
                }
                writer.write(SemanticRecords.candidate(row, seed, identity));
                emitted++;
            }
        }
        boolean complete = !truncated;
        writer.write(SemanticRecords.seedEvidence(seed, null, emitted, complete,
                complete ? null : "RESULT_LIMIT", !complete, identity));
        return new Result(1, emitted, complete, !complete);
    }

    private void validateSemanticOptions() {
        boolean hasTerm = term != null && !term.isBlank();
        boolean hasName = name != null && !name.isBlank();
        if (!hasTerm && !hasName) throw new IllegalArgumentException("provide a search term or --name");
        if (hasTerm && hasName) throw new IllegalArgumentException(
                "search term and --name are mutually exclusive");
        if (byAnnotation && !hasTerm) throw new IllegalArgumentException(
                "--by-annotation requires a search term");
        if (includeMeta && !byAnnotation) throw new IllegalArgumentException(
                "--include-meta requires --by-annotation");
        if (kind != null && !kind.isBlank()) {
            String normalized = kind.toLowerCase(Locale.ROOT);
            if (!List.of("type", "callable", "value", "artifact", "component", "config_entity", "entity")
                    .contains(normalized)) {
                // Uppercase storage kinds remain accepted during the transition.
                kind = CliValidation.kind(kind);
            } else {
                kind = normalized;
            }
        }
        CliValidation.positive("--limit", limit);
        if (limit > SemanticRecords.MAX_LIMIT) throw new IllegalArgumentException(
                "--limit must be <= " + SemanticRecords.MAX_LIMIT);
        CliValidation.nonNegative("--offset", offset);
    }

}
