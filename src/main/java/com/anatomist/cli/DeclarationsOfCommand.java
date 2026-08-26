package com.anatomist.cli;

import com.anatomist.query.DeclarationQueryService;
import com.anatomist.query.DeclarationRow;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryBudget;
import com.anatomist.query.QueryEvidence;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "declarations-of", mixinStandardHelpOptions = true,
        description = "Enumerate AST-backed Java type, method, and constructor declarations in one indexed file.",
        footer = "%nExample:%n  anatomist declarations-of --file src/main/java/com/example/AuthenticationService.java --visibility public,protected --kind type,method --top-level-types --direct-members --format json")
public final class DeclarationsOfCommand implements Callable<Integer> {
    private static final Set<String> VISIBILITIES = Set.of("public", "protected", "private", "package");
    private static final Set<String> KINDS = Set.of("type", "method", "constructor");

    @Option(names = "--file", required = true,
            description = "Normalized project-relative .java path (forward slashes).")
    String file;
    @Option(names = "--index", description = "SQLite index path.") Path index;
    @Option(names = "--module", description = "Restrict to one indexed module.") String module;
    @Option(names = "--scope", defaultValue = "MAIN",
            description = "Source scope: MAIN | TEST | GENERATED | ALL (default MAIN).") String scope;
    @Option(names = "--visibility", description = "Comma-separated: public,protected,private,package.") String visibility;
    @Option(names = "--kind", description = "Comma-separated: type,method,constructor.") String kind;
    @Option(names = "--top-level-types", description = "Exclude nested type declarations; callable filtering is unchanged.")
    boolean topLevelTypes;
    @Option(names = "--direct-members", description = "Keep only methods/constructors directly owned by a top-level type.")
    boolean directMembers;
    @Option(names = "--include-synthetic", description = "Include index-synthesized declarations when available.")
    boolean includeSynthetic;
    @Option(names = "--limit", defaultValue = "100", description = "Page size, 1..1000 (default 100).") int limit;
    @Option(names = "--offset", defaultValue = "0", description = "Rows to skip (default 0).") int offset;
    @Option(names = "--format", defaultValue = "json", description = "Output format: json.") String format;

    @Override public Integer call() {
        Path db = index == null ? null : index.toAbsolutePath().normalize();
        try {
            validate();
            db = IndexPath.resolve(index);
            try (QueryService service = new QueryService(db)) {
                DeclarationQueryService declarations = new DeclarationQueryService(service.connection());
                declarations.verifyFile(db, file, module, scope);
                Set<String> visibilities = csv(visibility, VISIBILITIES, "--visibility");
                Set<String> kinds = csv(kind, KINDS, "--kind");
                int total = declarations.count(file, module, scope, visibilities, kinds,
                        topLevelTypes, directMembers, includeSynthetic);
                List<DeclarationRow> results = declarations.find(file, module, scope, visibilities, kinds,
                        topLevelTypes, directMembers, includeSynthetic, limit, offset);
                QueryEnvelope envelope = new QueryEnvelope(query(), results);
                Disclosure.putPaging(envelope, total, limit, offset);
                Disclosure.putBudget(envelope, "rows", results.size(), total);
                envelope.evidence = QueryEvidence.positiveComplete();
                if (Boolean.TRUE.equals(envelope.stats.get("truncated"))) {
                    envelope.nextQueries = List.of(queryWithOffset(((Number) envelope.stats.get("next_offset")).intValue()));
                }
                JsonFormatter.emit(System.out, envelope);
                return 0;
            }
        } catch (DeclarationQueryService.DeclarationQueryException failure) {
            return fail(failure.code(), failure.getMessage());
        } catch (IllegalStateException failure) {
            String message = failure.getMessage() == null ? "index query failed" : failure.getMessage();
            String code = message.startsWith("SCHEMA_MISMATCH") ? "SCHEMA_MISMATCH" : "GRAPH_INTEGRITY_FAILED";
            return fail(code, message);
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && (failure.getMessage().contains("index db not found")
                    || failure.getMessage().contains("no index db found"))) return fail("INDEX_MISSING", failure.getMessage());
            return CliValidation.emit(failure);
        }
    }

    private void validate() {
        format = CliValidation.choice("--format", format, "json");
        scope = CliValidation.scope(scope, true);
        CliValidation.nonNegative("--offset", offset);
        CliValidation.positive("--limit", limit);
        if (limit > 1000) throw new IllegalArgumentException("--limit must be <= 1000; got " + limit);
        if (file == null || file.isBlank()) throw new IllegalArgumentException("--file is required");
        if (file.indexOf('\\') >= 0 || Path.of(file).isAbsolute() || !file.endsWith(".java")) {
            throw new IllegalArgumentException("--file must be a normalized project-relative .java path using forward slashes");
        }
        Path normalized = Path.of(file).normalize();
        String rendered = normalized.toString().replace(java.io.File.separatorChar, '/');
        if (rendered.startsWith("../") || ".".equals(rendered) || !rendered.equals(file)) {
            throw new IllegalArgumentException("--file must be normalized and remain inside the indexed project: " + file);
        }
        csv(visibility, VISIBILITIES, "--visibility");
        csv(kind, KINDS, "--kind");
    }

    private static Set<String> csv(String raw, Set<String> allowed, String option) {
        if (raw == null || raw.isBlank()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Arrays.stream(raw.split(",", -1)).map(String::trim).map(v -> v.toLowerCase(Locale.ROOT)).forEach(value -> {
            if (value.isEmpty() || !allowed.contains(value)) {
                throw new IllegalArgumentException(option + " must contain only " + String.join(",", allowed)
                        + "; got " + raw);
            }
            values.add(value);
        });
        return Set.copyOf(values);
    }

    private int fail(String code, String message) {
        QueryEnvelope envelope = new QueryEnvelope(query(), List.of());
        envelope.stats.clear(); envelope.stats.put("total", 0); envelope.stats.put("offset", offset);
        envelope.stats.put("limit", limit); envelope.stats.put("truncated", false);
        envelope.budget = new QueryBudget("rows", 0, 0, false);
        envelope.evidence = QueryEvidence.indeterminate(code, message);
        JsonFormatter.emit(System.out, envelope); return 3;
    }

    private String query() { return queryWithOffset(offset); }
    private String queryWithOffset(int value) {
        StringBuilder out = new StringBuilder("declarations-of --file ").append(file);
        add(out, "--scope", scope); add(out, "--module", module); add(out, "--visibility", visibility);
        add(out, "--kind", kind); flag(out, topLevelTypes, "--top-level-types");
        flag(out, directMembers, "--direct-members"); flag(out, includeSynthetic, "--include-synthetic");
        add(out, "--limit", String.valueOf(limit)); if (value != 0) add(out, "--offset", String.valueOf(value));
        if (index != null) add(out, "--index", index.toString()); return out.toString();
    }
    private static void add(StringBuilder out, String option, String value) {
        if (value != null && !value.isBlank()) out.append(' ').append(option).append(' ').append(value);
    }
    private static void flag(StringBuilder out, boolean enabled, String option) { if (enabled) out.append(' ').append(option); }
}
