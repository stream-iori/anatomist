package com.anatomist.cli;

import com.anatomist.model.SemanticAnnotation;
import com.anatomist.json.Json;
import com.anatomist.store.SqliteStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "annotate",
        mixinStandardHelpOptions = true,
        description = "Write user-supplied semantic annotations to the index.")
public class AnnotateCommand implements Callable<Integer> {

    /** Sources allowed to be written by the CLI. JAVADOC is generated from source comments;
     *  manual entry would conflict. CONVENTION is reserved and not user-writable. */
    static final Set<String> ALLOWED_SOURCES = Set.of("DOC", "LLM");
    static final Set<String> ALLOWED_CONFIDENCES = Set.of("HIGH", "MEDIUM", "LOW");

    @Parameters(index = "0", arity = "0..1",
            description = "Node ID (FQN). Omit when using --from-json.")
    String nodeId;

    @Option(names = "--label", description = "Label (free text).")
    String label;

    @Option(names = "--category", description = "Category (free text, e.g. REVIEWED).")
    String category;

    @Option(names = "--context", description = "Context (free text).")
    String context;

    @Option(names = "--description", description = "Description (free text).")
    String description;

    @Option(names = "--source", description = "Source: DOC | LLM (default LLM).")
    String source = "LLM";

    @Option(names = "--confidence", description = "Confidence: HIGH | MEDIUM | LOW (default MEDIUM).")
    String confidence = "MEDIUM";

    @Option(names = "--from-json", description = "Read a JSON array of annotation objects instead of CLI args.")
    Path fromJson;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/indexes/<repo-key>/index.db).")
    Path index;

    @Option(names = "--format", description = "Output format: text | json.", defaultValue = "text")
    String format;

    @Option(names = "--module", description = "Select a module when symbol_id is not globally unique.")
    String module;

    @Option(names = "--scope", description = "Select MAIN | TEST | GENERATED (default MAIN).",
            defaultValue = "MAIN")
    String scope;

    @Override
    public Integer call() {
        try {
            format = CliValidation.choice("--format", format, "text", "json");
            scope = CliValidation.scope(scope, false);
            return execute();
        } catch (IllegalArgumentException failure) {
            return CliValidation.emit(failure);
        } catch (Exception failure) {
            return emitError("ANNOTATE_FAILED", failure.getMessage(), 1);
        }
    }

    private Integer execute() throws Exception {
        Path db = IndexPath.resolve(index);
        com.anatomist.version.SnapshotFiles.requireMutable(db);

        List<SemanticAnnotation> batch;
        if (fromJson != null) {
            batch = parseBatch(fromJson);
        } else {
            if (nodeId == null || nodeId.isEmpty()) {
                return emitError("INVALID_ARGUMENT", "node-id is required unless --from-json is used");
            }
            if (category == null || category.isEmpty()) {
                return emitError("INVALID_ARGUMENT", "--category is required");
            }
            SemanticAnnotation sa = new SemanticAnnotation();
            sa.nodeId = nodeId;
            sa.category = category;
            sa.businessLabel = label;
            sa.businessDescription = description;
            sa.domainContext = context;
            sa.source = source;
            sa.confidence = confidence;
            batch = List.of(sa);
        }

        for (SemanticAnnotation sa : batch) {
            String err = validate(sa);
            if (err != null) {
                return emitError("INVALID_ARGUMENT", err);
            }
        }

        try (com.anatomist.store.IndexOperationLock operation =
                     com.anatomist.store.IndexOperationLock.forWrite(db);
             com.anatomist.store.IndexLock wLock = com.anatomist.store.IndexLock.forWrite(db);
             SqliteStore store = new SqliteStore(db)) {
            String resolutionError = resolveStorageIds(store, batch, module, scope);
            if (resolutionError != null) {
                String code = resolutionError.startsWith("ambiguous")
                        ? "SYMBOL_AMBIGUOUS" : "SYMBOL_NOT_FOUND";
                return emitError(code, resolutionError);
            }
            store.upsertSemanticAnnotations(batch);
        }
        if ("json".equals(format)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("command", "annotate");
            out.put("status", "ok");
            out.put("annotated", batch.size());
            System.out.println(Json.writePretty(out));
        } else {
            System.out.println("Annotated " + batch.size() + " node(s).");
        }
        return 0;
    }

    private int emitError(String code, String message) {
        return emitError(code, message, 2);
    }

    private int emitError(String code, String message, int exitCode) {
        if ("json".equalsIgnoreCase(format)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("command", "annotate");
            out.put("status", "error");
            out.put("code", code);
            out.put("message", message == null ? "unknown error" : message);
            System.out.println(Json.writePretty(out));
        } else {
            System.err.println("ERROR: " + (message == null ? "unknown error" : message));
        }
        return exitCode;
    }

    private static String validate(SemanticAnnotation sa) {
        if (sa.nodeId == null || sa.nodeId.isEmpty()) return "node_id is required";
        if (sa.category == null || sa.category.isEmpty()) return "category is required";
        if (sa.source == null || sa.source.isEmpty()) return "source is required";
        if (!ALLOWED_SOURCES.contains(sa.source)) {
            return "source must be DOC or LLM (CONVENTION is reserved and JAVADOC is generated); got " + sa.source;
        }
        if (sa.confidence == null || sa.confidence.isEmpty()) sa.confidence = "MEDIUM";
        if (!ALLOWED_CONFIDENCES.contains(sa.confidence)) {
            return "confidence must be HIGH | MEDIUM | LOW; got " + sa.confidence;
        }
        return null;
    }

    private static List<SemanticAnnotation> parseBatch(Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("--from-json file not found: " + file);
        }
        String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        List<SemanticAnnotation> list = SemanticAnnotation.listFromJson(text);
        for (SemanticAnnotation sa : list) {
            if (sa.source == null) sa.source = "LLM";
            if (sa.confidence == null) sa.confidence = "MEDIUM";
        }
        return list == null ? new ArrayList<>() : list;
    }

    private static String resolveStorageIds(SqliteStore store, List<SemanticAnnotation> batch,
                                            String module, String scope) throws Exception {
        String selectedScope = scope == null ? "MAIN" : scope.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("MAIN", "TEST", "GENERATED").contains(selectedScope)) {
            return "scope must be MAIN, TEST, or GENERATED: " + scope;
        }
        Connection c = store.connection();
        String sql = "SELECT id FROM nodes WHERE symbol_id=? AND scope=?"
                + (module == null || module.isBlank() ? "" : " AND module=?")
                + " ORDER BY id";
        String exactSql = "SELECT id FROM nodes WHERE id=? AND scope=?"
                + (module == null || module.isBlank() ? "" : " AND module=?");
        try (PreparedStatement exact = c.prepareStatement(exactSql);
             PreparedStatement symbolic = c.prepareStatement(sql)) {
            for (SemanticAnnotation sa : batch) {
                String requested = sa.nodeId;
                exact.setString(1, requested);
                exact.setString(2, selectedScope);
                if (module != null && !module.isBlank()) exact.setString(3, module);
                try (ResultSet rs = exact.executeQuery()) {
                    if (rs.next()) {
                        sa.nodeId = rs.getString(1);
                        continue;
                    }
                }
                symbolic.setString(1, requested);
                symbolic.setString(2, selectedScope);
                if (module != null && !module.isBlank()) symbolic.setString(3, module);
                List<String> matches = new ArrayList<>();
                try (ResultSet rs = symbolic.executeQuery()) {
                    while (rs.next()) matches.add(rs.getString(1));
                }
                if (matches.isEmpty()) return "node not found: " + requested;
                if (matches.size() > 1) {
                    return "ambiguous node '" + requested + "'; specify --module (matches: "
                            + String.join(", ", matches) + ")";
                }
                sa.nodeId = matches.get(0);
            }
        }
        return null;
    }
}
