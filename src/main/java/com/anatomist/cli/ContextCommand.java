package com.anatomist.cli;

import com.anatomist.query.ContextResult;
import com.anatomist.query.EnrichResult;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.MarkdownFormatter;
import com.anatomist.model.GraphConstants;
import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "context",
        mixinStandardHelpOptions = true,
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

    @Option(names = "--members-limit", description = "Max contained members to emit (default 0 = all).")
    int membersLimit = 0;

    @Option(names = "--members-offset", description = "Skip N contained members for pagination.")
    int membersOffset = 0;

    @Option(names = "--methods-only", description = "Emit only METHOD/CONSTRUCTOR members.")
    boolean methodsOnly;

    @Option(names = "--fields-only", description = "Emit only FIELD members.")
    boolean fieldsOnly;

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
            List<NodeRow> candidates = q.resolveNodeRows(target);
            if (candidates.size() > 1) {
                QueryEnvelope env = new QueryEnvelope(buildQueryString(), candidates);
                env.stats.clear();
                env.stats.put("total", 0);
                env.stats.put("ambiguous", true);
                env.stats.put("candidates", candidates.size());
                env.stats.put("reason", "target_resolves_to_multiple_nodes");
                env.nextQueries = candidates.stream()
                        .map(n -> "anatomist context " + n.qualifiedName + " --index " + db)
                        .toList();
                JsonFormatter.emit(System.out, env);
                return 2;
            }
            ContextResult r = q.context(target, withCallees == null ? 0 : withCallees);
            int membersTotal = 0;
            int safeOffset = 0;
            boolean membersTruncated = false;
            if (r != null) {
                java.util.List<com.anatomist.query.NodeRow> members = r.members;
                if (methodsOnly) {
                    members = members.stream()
                            .filter(m -> GraphConstants.METHOD_KINDS.contains(m.kind))
                            .toList();
                } else if (fieldsOnly) {
                    members = members.stream().filter(m -> GraphConstants.Kind.FIELD.equals(m.kind)).toList();
                }
                membersTotal = members.size();
                safeOffset = Math.max(0, Math.min(membersOffset, membersTotal));
                if (membersLimit > 0) {
                    int end = Math.min(safeOffset + membersLimit, membersTotal);
                    r.members = new java.util.ArrayList<>(members.subList(safeOffset, end));
                    membersTruncated = end < membersTotal;
                } else {
                    r.members = new java.util.ArrayList<>(members.subList(safeOffset, membersTotal));
                }
            }
            QueryEnvelope env = new QueryEnvelope(buildQueryString(),
                    r == null ? List.of() : List.of(r));
            env.stats.clear();
            if (r != null) env.stats.putAll(r.toStats());
            else env.stats.put("total", 0);
            boolean memberPagingRequested = membersLimit > 0 || membersOffset > 0 || methodsOnly || fieldsOnly;
            if (r != null && memberPagingRequested) {
                env.stats.put("members_total", membersTotal);
                env.stats.put("members_offset", safeOffset);
                env.stats.put("members_limit", membersLimit > 0 ? membersLimit : membersTotal);
                env.stats.put("members_truncated", membersTruncated);
                if (membersTruncated) {
                    int nextOffset = safeOffset + membersLimit;
                    env.stats.put("members_next_offset", nextOffset);
                    env.nextQueries = List.of(buildQueryString().replaceAll(" --members-offset \\d+", "")
                            + " --members-offset " + nextOffset);
                }
                Disclosure.putBudget(env, "members", r.members.size(), membersTotal);
            }
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
        if (membersLimit > 0) sb.append(" --members-limit ").append(membersLimit);
        if (membersOffset > 0) sb.append(" --members-offset ").append(membersOffset);
        if (methodsOnly) sb.append(" --methods-only");
        if (fieldsOnly) sb.append(" --fields-only");
        if (withDocs) sb.append(" --with-docs");
        return sb.toString();
    }
}
