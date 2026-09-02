package com.anatomist.cli;

import com.anatomist.query.ContextResult;
import com.anatomist.query.EnrichResult;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.MarkdownFormatter;
import com.anatomist.model.GraphConstants;
import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryCoverageService;
import com.anatomist.query.QueryService;
import com.anatomist.query.SourceContext;
import com.anatomist.query.SourceRequest;
import com.anatomist.query.SymbolResolution;
import com.anatomist.query.SymbolResolutionException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "context",
        mixinStandardHelpOptions = true,
        description = "Show node + contained members + annotations. "
                    + "Add --source for the exact source-backed declaration. "
                    + "Add --with-callees[=N] for outgoing CALLS. "
                    + "Add --enrich for semantic annotations, docs and suggested queries.")
public class ContextCommand implements Callable<Integer> {

    private static final int DEFAULT_SOURCE_LIMIT = 200;
    private static final int MAX_SOURCE_LIMIT = 1000;

    @Parameters(index = "0", arity = "0..1", description = "Unique type, method, or constructor selector; use a full signature for overloaded methods.")
    String target;

    @Option(names = "--enrich",
            description = "Aggregate enriched view: semantic annotations + docs + suggested queries.")
    boolean enrich;

    @Option(names = "--package",
            description = "Package name; requires --enrich and is mutually exclusive with positional target.")
    String pkg;

    @Option(names = "--with-callees", arity = "0..1", fallbackValue = "1",
            description = "Include outgoing CALLS, N hops (default 1 when flag present).")
    Integer withCallees;

    @Option(names = "--source", description = "Return the exact source-backed declaration.")
    boolean source;

    @Option(names = "--source-limit", description = "Maximum source lines to emit (default 200, max 1000).")
    Integer sourceLimit;

    @Option(names = "--source-offset", description = "Zero-based line offset within the declaration.")
    Integer sourceOffset;

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

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/indexes/<repo-key>/index.db).")
    Path index;

    @Option(names = "--module", description = "Restrict symbol resolution to one module.") String module;
    @Option(names = "--scope", description = "MAIN | TEST | GENERATED | ALL.", defaultValue = "MAIN") String scope;

    @Override
    public Integer call() {
        try {
            validateOptions();
            Path db = IndexPath.resolve(index);
            if (enrich) {
                return callEnrich(db);
            }
            return callContext(db);
        } catch (IllegalArgumentException failure) {
            return CliValidation.emit(failure);
        }
    }

    private void validateOptions() {
        if (target != null && pkg != null) {
            throw new IllegalArgumentException("specify either a positional target or --package, not both");
        }
        if (target == null && pkg == null) {
            throw new IllegalArgumentException("specify a target or --package");
        }
        if (pkg != null && !enrich) {
            throw new IllegalArgumentException("--package requires --enrich");
        }
        if (withDocs && !enrich) {
            throw new IllegalArgumentException("--with-docs requires --enrich");
        }
        if (source && (enrich || pkg != null)) {
            throw new IllegalArgumentException("--source is not supported with --enrich or --package");
        }
        if (!source && (sourceLimit != null || sourceOffset != null)) {
            throw new IllegalArgumentException("--source-limit and --source-offset require --source");
        }
        if (methodsOnly && fieldsOnly) {
            throw new IllegalArgumentException("--methods-only and --fields-only are mutually exclusive");
        }
        if (enrich && (membersLimit != 0 || membersOffset != 0 || methodsOnly || fieldsOnly)) {
            throw new IllegalArgumentException("member paging options are not supported with --enrich");
        }
        if (pkg != null && withCallees != null) {
            throw new IllegalArgumentException("--with-callees is not supported with --package");
        }
        scope = CliValidation.scope(scope, true);
        CliValidation.nonNegative("--members-limit", membersLimit);
        CliValidation.nonNegative("--members-offset", membersOffset);
        if (withCallees != null) CliValidation.nonNegative("--with-callees", withCallees);
        if (sourceLimit != null) {
            CliValidation.positive("--source-limit", sourceLimit);
            if (sourceLimit > MAX_SOURCE_LIMIT) {
                throw new IllegalArgumentException("--source-limit must be <= " + MAX_SOURCE_LIMIT
                        + "; got " + sourceLimit);
            }
        }
        if (sourceOffset != null) CliValidation.nonNegative("--source-offset", sourceOffset);
        format = CliValidation.choice("--format", format, "markdown", "json");
    }

    private int callContext(Path db) {
        try (QueryService q = new QueryService(db)) {
            q.selectNodes(module, scope);
            SymbolResolution resolution = q.resolveNode(target);
            NodeRow selected = resolution.requireUnique();
            SourceRequest sourceRequest = source
                    ? new SourceRequest(effectiveSourceLimit(), effectiveSourceOffset()) : null;
            ContextResult r = q.context(selected.id, withCallees == null ? 0 : withCallees, sourceRequest);
            List<String> nextQueries = new java.util.ArrayList<>();
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
                    nextQueries.add(withIndex(buildQueryString(nextOffset, true,
                            effectiveSourceOffset(), false, source), db));
                }
                Disclosure.putBudget(env, "members", r.members.size(), membersTotal);
            }
            SourceContext sourceContext = r == null ? null : r.source;
            if (sourceContext != null && "ok".equals(sourceContext.status)
                    && Boolean.TRUE.equals(sourceContext.truncated)) {
                int emitted = sourceContext.startLine == null ? 0
                        : sourceContext.endLine - sourceContext.startLine + 1;
                int nextOffset = sourceContext.offset + emitted;
                nextQueries.add(withIndex(buildQueryString(safeOffset, false,
                        nextOffset, true, true), db));
            }
            if (!nextQueries.isEmpty()) env.nextQueries = List.copyOf(nextQueries);
            boolean fatalSource = sourceContext != null && sourceContext.fatal();
            if (fatalSource) {
                env.evidence = com.anatomist.query.QueryEvidence.indeterminate(
                        sourceContext.warningCode, sourceContext.warningMessage);
            } else {
                attachEvidence(q, env, r != null);
            }
            String effectiveFormat = format == null ? "json" : format;
            if ("markdown".equals(effectiveFormat)) {
                System.out.print(MarkdownFormatter.format(r, nextQueries));
            } else {
                JsonFormatter.emit(System.out, env);
            }
            return r == null ? 2 : fatalSource ? 3 : 0;
        } catch (SymbolResolutionException failure) {
            return SymbolResolutionOutput.emit(failure, db, module, scope);
        }
    }

    private int callEnrich(Path db) {
        try (QueryService q = new QueryService(db)) {
            q.selectNodes(module, scope);
            int depth = withCallees != null ? withCallees : 1;
            String selected = target;
            if (pkg == null) selected = q.resolveNode(target).requireUnique().id;
            EnrichResult r = pkg != null
                    ? q.enrichPackage(pkg, withDocs)
                    : q.enrichNode(selected, depth, withDocs);
            if (r == null) {
                System.err.println("ERROR: no node or package matches the target.");
                return 2;
            }
            String effectiveFormat = format != null ? format : "markdown";
            if ("json".equals(effectiveFormat)) {
                QueryEnvelope env = new QueryEnvelope(buildQueryString(), List.of(r));
                env.stats.clear();
                env.stats.putAll(r.toStats());
                attachEvidence(q, env, true);
                JsonFormatter.emit(System.out, env);
            } else {
                System.out.print(MarkdownFormatter.format(r));
            }
            return 0;
        } catch (SymbolResolutionException failure) {
            return SymbolResolutionOutput.emit(failure, db, module, scope);
        }
    }

    private void attachEvidence(QueryService service, QueryEnvelope envelope, boolean positive) {
        envelope.evidence = new QueryCoverageService(service.connection()).assess(
                QueryCoverageService.Capability.DECLARATION,
                target == null ? List.of() : List.of(target),
                module, scope, positive, false);
    }

    private String buildQueryString() {
        return buildQueryString(membersOffset, false, effectiveSourceOffset(), false, false);
    }

    private String buildQueryString(int effectiveMembersOffset, boolean memberOffsetLast,
                                    int effectiveSourceOffset, boolean sourceOffsetLast,
                                    boolean forceSourceLimit) {
        List<String> args = new java.util.ArrayList<>();
        args.add("context");
        Disclosure.addFlag(args, enrich, "--enrich");
        if (target != null) args.add(target);
        if (pkg != null) {
            args.add("--package");
            args.add(pkg);
        }
        Disclosure.addFlag(args, source, "--source");
        if (withCallees != null) args.add("--with-callees=" + withCallees);
        if (format != null) {
            args.add("--format");
            args.add(format);
        }
        if (membersLimit > 0) {
            Disclosure.addOption(args, "--members-limit", membersLimit);
        }
        if (effectiveMembersOffset > 0 && !memberOffsetLast) {
            Disclosure.addOption(args, "--members-offset", effectiveMembersOffset);
        }
        if (source && (sourceLimit != null || forceSourceLimit)) {
            Disclosure.addOption(args, "--source-limit", effectiveSourceLimit());
        }
        if (source && effectiveSourceOffset > 0 && !sourceOffsetLast) {
            Disclosure.addOption(args, "--source-offset", effectiveSourceOffset);
        }
        Disclosure.addFlag(args, methodsOnly, "--methods-only");
        Disclosure.addFlag(args, fieldsOnly, "--fields-only");
        Disclosure.addFlag(args, withDocs, "--with-docs");
        Disclosure.addOption(args, "--module", module);
        Disclosure.addOption(args, "--scope", scope);
        if (effectiveMembersOffset > 0 && memberOffsetLast) {
            Disclosure.addOption(args, "--members-offset", effectiveMembersOffset);
        }
        if (source && effectiveSourceOffset > 0 && sourceOffsetLast) {
            Disclosure.addOption(args, "--source-offset", effectiveSourceOffset);
        }
        return String.join(" ", args);
    }

    private int effectiveSourceLimit() {
        return sourceLimit == null ? DEFAULT_SOURCE_LIMIT : sourceLimit;
    }

    private int effectiveSourceOffset() {
        return sourceOffset == null ? 0 : sourceOffset;
    }

    private static String withIndex(String query, Path db) {
        return query + " " + Disclosure.renderCommand(List.of("--index", db.toString()));
    }
}
