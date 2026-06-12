package com.anatomist.cli;

import com.anatomist.model.ArchRole;
import com.anatomist.model.SemanticAnnotation;
import com.anatomist.semantic.ArchRoleInferrer;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "annotate",
        description = "Write business-semantic annotations to the index. "
                    + "Default source=LLM, confidence=MEDIUM; (node_id, category, source) is upsert-keyed.")
public class AnnotateCommand implements Callable<Integer> {

    /** Sources allowed to be written by the CLI. CONVENTION and JAVADOC are auto-generated
     *  by SemanticPostProcessor / future extractors — manual entry would conflict. */
    static final Set<String> ALLOWED_SOURCES = Set.of("DOC", "LLM");
    static final Set<String> ALLOWED_CONFIDENCES = Set.of("HIGH", "MEDIUM", "LOW");

    @Parameters(index = "0", arity = "0..1",
            description = "Node ID (FQN). Omit when using --from-json.")
    String nodeId;

    @Option(names = "--label", description = "Business label (free text).")
    String label;

    @Option(names = "--category", description = "Business category (e.g. BUSINESS_SERVICE).")
    String category;

    @Option(names = "--context", description = "Domain context (free text).")
    String context;

    @Option(names = "--description", description = "Business description (free text).")
    String description;

    @Option(names = "--source", description = "Source: DOC | LLM (default LLM).")
    String source = "LLM";

    @Option(names = "--confidence", description = "Confidence: HIGH | MEDIUM | LOW (default MEDIUM).")
    String confidence = "MEDIUM";

    @Option(names = "--from-json", description = "Read a JSON array of annotation objects instead of CLI args.")
    Path fromJson;

    @Option(names = "--auto", description = "Auto-infer architecture roles (DDD layers) via L1 annotation + L2 call-pattern rules, write to arch_roles table.")
    boolean auto;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/<repo>/index.db).")
    Path index;

    @Override
    public Integer call() throws Exception {
        Path db = IndexPath.resolve(index);

        if (auto) {
            return runAutoInference(db);
        }

        List<SemanticAnnotation> batch;
        if (fromJson != null) {
            batch = parseBatch(fromJson);
        } else {
            if (nodeId == null || nodeId.isEmpty()) {
                System.err.println("ERROR: node-id is required unless --from-json is used.");
                return 1;
            }
            if (category == null || category.isEmpty()) {
                System.err.println("ERROR: --category is required.");
                return 1;
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
                System.err.println("ERROR: " + err);
                return 1;
            }
        }

        try (SqliteStore store = new SqliteStore(db)) {
            warnOnMissingNodes(store, batch);
            store.upsertSemanticAnnotations(batch);
        }
        System.out.println("Annotated " + batch.size() + " node(s).");
        return 0;
    }

    private int runAutoInference(Path db) {
        try (SqliteStore store = new SqliteStore(db)) {
            ArchRoleInferrer inferrer = new ArchRoleInferrer(store);
            List<ArchRole> roles = inferrer.infer();
            if (roles.isEmpty()) {
                System.out.println("No architecture roles inferred.");
                return 0;
            }
            store.upsertArchRoles(roles);

            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (ArchRole r : roles) {
                counts.merge(r.role, 1, Integer::sum);
            }
            System.out.println("Inferred " + roles.size() + " architecture role(s):");
            for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                System.out.println("  " + e.getKey() + ": " + e.getValue());
            }
            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            return 1;
        }
    }

    private static String validate(SemanticAnnotation sa) {
        if (sa.nodeId == null || sa.nodeId.isEmpty()) return "node_id is required";
        if (sa.category == null || sa.category.isEmpty()) return "category is required";
        if (sa.source == null || sa.source.isEmpty()) return "source is required";
        if (!ALLOWED_SOURCES.contains(sa.source)) {
            return "source must be DOC or LLM (CONVENTION and JAVADOC are auto-generated); got " + sa.source;
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

    private static void warnOnMissingNodes(SqliteStore store, List<SemanticAnnotation> batch) throws Exception {
        Connection c = store.connection();
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM nodes WHERE id = ?")) {
            for (SemanticAnnotation sa : batch) {
                ps.setString(1, sa.nodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        System.err.println("WARN: node not found, annotation created with dangling node_id: "
                                + sa.nodeId);
                    }
                }
            }
        }
    }
}
