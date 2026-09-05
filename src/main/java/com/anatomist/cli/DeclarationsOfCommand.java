package com.anatomist.cli;

import com.anatomist.query.DeclarationQueryService;
import com.anatomist.query.DeclarationRow;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.query.semantic.SemanticRecords;
import com.anatomist.query.semantic.SemanticStreamWriter;
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
        description = "Enumerate declarations in one indexed source file.",
        footer = "%nAccepts: CLI file selector%nEmits: entity + evidence%n%nExample:%n  anatomist declarations-of --file src/main/java/com/example/AuthenticationService.java --visibility public,protected --kind type,method --format ndjson")
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
    @Option(names = "--format", defaultValue = "ndjson", description = "Semantic projection: ndjson | json | table.") String format;

    @Override public Integer call() {
        Path db = index == null ? null : index.toAbsolutePath().normalize();
        try {
            validate();
            db = IndexPath.resolve(index);
            try (QueryService service = new QueryService(db);
                 SemanticStreamWriter writer = new SemanticStreamWriter(System.out, format)) {
                SemanticIdentity identity = SemanticIdentity.read(service.connection());
                DeclarationQueryService declarations = new DeclarationQueryService(service.connection());
                declarations.verifyFile(db, file, module, scope);
                Set<String> visibilities = csv(visibility, VISIBILITIES, "--visibility");
                Set<String> kinds = csv(kind, KINDS, "--kind");
                int total = declarations.count(file, module, scope, visibilities, kinds,
                        topLevelTypes, directMembers, includeSynthetic);
                List<DeclarationRow> results = declarations.find(file, module, scope, visibilities, kinds,
                        topLevelTypes, directMembers, includeSynthetic, limit, offset);
                String root = SemanticRecords.rootSeed("declarations-of", file);
                for (DeclarationRow row : results) {
                    String seed = SemanticRecords.childSeed(root, "declaration", row.nodeId);
                    writer.write(entity(row, seed, root, identity));
                    writer.write(SemanticRecords.seedEvidence(seed, root, 1, true, null, identity));
                }
                boolean complete = offset + results.size() >= total;
                writer.write(SemanticRecords.seedEvidence(root, null, results.size(), complete,
                        complete ? null : "RESULT_LIMIT", !complete, identity));
                writer.write(SemanticRecords.streamEvidence(1, results.size(), complete,
                        !complete, identity));
                return 0;
            }
        } catch (DeclarationQueryService.DeclarationQueryException failure) {
            System.err.println("ERROR[" + failure.code() + "]: " + failure.getMessage());
            return 3;
        } catch (IllegalStateException failure) {
            String message = failure.getMessage() == null ? "index query failed" : failure.getMessage();
            String code = message.startsWith("SCHEMA_MISMATCH") ? "SCHEMA_MISMATCH" : "GRAPH_INTEGRITY_FAILED";
            System.err.println("ERROR[" + code + "]: " + message);
            return 3;
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && (failure.getMessage().contains("index db not found")
                    || failure.getMessage().contains("no index db found"))) {
                System.err.println("ERROR[INDEX_MISSING]: " + failure.getMessage());
                return 3;
            }
            return CliValidation.emit(failure);
        }
    }

    private static java.util.Map<String, Object> entity(DeclarationRow row, String seed,
                                                         String parent, SemanticIdentity identity) {
        var out = SemanticRecords.common("entity", seed, parent, identity);
        out.put("id", row.nodeId);
        out.put("symbol_id", row.symbolId);
        out.put("domain", "language"); out.put("language", "java");
        out.put("kind", "type".equals(row.declarationKind) ? "type" : "callable");
        out.put("name", row.label); out.put("qualified_name", row.qualifiedName);
        out.put("module", row.module); out.put("scope", row.scope);
        out.put("producer_id", row.producerId); out.put("origin", row.synthetic ? "derived" : "extracted");
        out.put("resolution_status", "exact");
        var source = new java.util.LinkedHashMap<String, Object>();
        source.put("file", row.sourceFile);
        Integer line = SemanticRecords.line(row.sourceLocation);
        if (line != null) source.put("start_line", line);
        out.put("source", source);
        var facets = new java.util.LinkedHashMap<String, Object>();
        facets.put("declaration_kind", row.declarationKind); facets.put("visibility", row.visibility);
        facets.put("storage_kind", row.kind); facets.put("modifiers", row.modifiers);
        facets.put("declared_modifiers", row.declaredModifiers);
        facets.put("implicit_modifiers", row.implicitModifiers);
        facets.put("direct_member", row.directMember);
        facets.put("nesting_depth", row.nestingDepth); facets.put("synthetic", row.synthetic);
        if (row.typeKind != null) facets.put("type_kind", row.typeKind);
        if (row.declaringType != null) facets.put("declaring_type", row.declaringType);
        if (row.lombok != null) facets.put("lombok", row.lombok);
        out.put("facets", facets);
        return out;
    }

    private void validate() {
        format = CliValidation.choice("--format", format, "ndjson", "json", "table");
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
