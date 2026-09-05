package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryService;
import com.anatomist.query.SearchService;
import com.anatomist.query.semantic.SemanticCursor;
import com.anatomist.query.semantic.SemanticCapabilityRegistry;
import com.anatomist.query.semantic.SemanticStreamWriter.BrokenPipeException;
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
        footer = "%nAccepts: CLI selector%nEmits: entity_candidate | result_count + evidence%nCapability: semantic-stream/v1, java-entity-lookup%n%nExamples:%n  search OrderService%n  search PaymentGateway --kind type --format ndjson%n  search --name '*Plugin' --kind type%n  search Facade --count%n  search @Deprecated --by-annotation")
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

    @Option(names = "--format", defaultValue = "ndjson",
            description = "Semantic projection: ndjson (default) | json | table.")
    String format;

    @Override
    public Integer call() {
        java.nio.file.Path db = IndexPath.resolve(index);
        try {
            scope = CliValidation.scope(scope, true);
            validateSemanticOptions();
            try (QueryService q = new QueryService(db);
                 SemanticStreamWriter writer = new SemanticStreamWriter(System.out, format)) {
                q.selectNodes(module, scope);
                SemanticIdentity identity = SemanticIdentity.read(q.connection());
                new SemanticCapabilityRegistry(q.connection()).require(
                        SemanticCapabilityRegistry.Capability.ENTITY_LOOKUP);
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
                            mode, selector, kind, SemanticRecords.MAX_LIMIT, 0)) {
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
                    writer.write(SemanticRecords.streamEvidence(1, 1, true, identity));
                    return 0;
                }
                try (SemanticCursor<NodeRow> rows = q.semanticSearchCursor(
                        mode, selector, kind, limit + 1, offset)) {
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
                writer.write(SemanticRecords.streamEvidence(1, emitted, complete,
                        !complete, identity));
            }
            return 0;
        } catch (BrokenPipeException ignored) {
            return 0;
        } catch (IllegalArgumentException failure) {
            return CliValidation.emit(failure);
        } catch (IllegalStateException failure) {
            System.err.println("ERROR: " + failure.getMessage());
            return 3;
        } catch (RuntimeException failure) {
            System.err.println("ERROR: " + failure.getMessage());
            return 1;
        }
    }

    private void validateSemanticOptions() {
        boolean hasTerm = term != null && !term.isBlank();
        boolean hasName = name != null && !name.isBlank();
        if (!hasTerm && !hasName) throw new IllegalArgumentException("provide a search term or --name");
        if (hasTerm && hasName) throw new IllegalArgumentException(
                "search term and --name are mutually exclusive");
        if (byAnnotation && !hasTerm) throw new IllegalArgumentException(
                "--by-annotation requires a search term");
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

    @Override
    protected com.anatomist.query.QueryEnvelope execute(QueryService q) {
        throw new UnsupportedOperationException("search is semantic-only in 1.0");
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
