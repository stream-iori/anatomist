package com.anatomist.store;

import com.anatomist.core.NodeKeyFactory;
import com.anatomist.core.SourceIdentity;
import com.anatomist.core.SourceIdentityResolver;
import com.anatomist.model.BeanRefTarget;
import com.anatomist.model.Annotation;
import com.anatomist.model.AnnotationMetaRelation;
import com.anatomist.model.Edge;
import com.anatomist.model.Declaration;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;
import com.anatomist.model.ProducerIds;
import com.anatomist.model.SymbolFact;
import com.anatomist.model.TypeRelationFact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * File-backed raw fact buffer used by both full and incremental indexing.
 * The sidecar intentionally has no final graph indexes or foreign keys.
 */
public final class StagedGraphStore implements AutoCloseable {

    private static final String ALIAS = "staged";
    private final Path path;
    private final SourceIdentityResolver identities;
    private final boolean reusable;
    private Connection connection;
    private int reboundExternalTargets;
    private int droppedDanglingFacts;

    public StagedGraphStore(Path indexPath, SourceIdentityResolver identities) {
        this(indexPath, identities, null);
    }

    public StagedGraphStore(Path indexPath, SourceIdentityResolver identities, Path reusablePath) {
        this.reusable = reusablePath != null;
        if (!reusable) cleanupOrphans(indexPath);
        this.path = reusable ? reusablePath : indexPath.resolveSibling(indexPath.getFileName() + ".stage-"
                + ProcessHandle.current().pid() + "-" + UUID.randomUUID() + ".db");
        this.identities = identities;
        try {
            boolean existing = reusable && Files.isRegularFile(path) && Files.size(path) > 0;
            if (!reusable) Files.deleteIfExists(path);
            if (existing) {
                try {
                    reset();
                } catch (SQLException incompatibleStage) {
                    closeConnection();
                    Files.deleteIfExists(path);
                    initSchema(connection());
                }
            } else {
                initSchema(connection());
            }
        } catch (Exception e) {
            closeConnection();
            try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            throw new RuntimeException("Failed to initialize staging database " + path, e);
        }
    }

    public Path path() {
        return path;
    }

    private static void cleanupOrphans(Path indexPath) {
        Path parent = indexPath.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) return;
        String prefix = indexPath.getFileName() + ".stage-";
        try (var files = Files.newDirectoryStream(parent, prefix + "*")) {
            for (Path candidate : files) Files.deleteIfExists(candidate);
        } catch (Exception e) {
            throw new RuntimeException("Failed to clean stale staging databases beside " + indexPath, e);
        }
    }

    /** Write extractor-level facts. Node identity is normalized now; references are resolved later. */
    public void writeRawBatch(ExtractionResult result) {
        writeBatch(result, false);
    }

    /** Write a batch already processed by GraphIdentityRewriter/GraphPostProcessor. */
    public void writeNormalizedBatch(ExtractionResult result) {
        writeBatch(result, true);
    }

    private void writeBatch(ExtractionResult result, boolean normalized) {
        if (result == null || result.factCount() == 0) return;
        Map<String, String> nodeSourceFiles = new HashMap<>();
        Map<String, Declaration> declarationRanges = new HashMap<>();
        for (Declaration declaration : result.declarations) {
            if (declaration.symbolId != null && declaration.beginLine != null) {
                declarationRanges.putIfAbsent(declaration.symbolId + "\n" + declaration.sourceFile,
                        declaration);
            }
        }
        for (Node node : result.nodes) {
            String symbol = symbolOf(node);
            Declaration declaration = declarationRanges.get(symbol + "\n" + node.sourceFile);
            if (declaration != null && node.beginLine == null) {
                node.beginLine = declaration.beginLine;
                node.beginColumn = declaration.beginColumn;
                node.endLine = declaration.endLine;
                node.endColumn = declaration.endColumn;
                node.sourceOrdinal = 0;
            }
            if (symbol != null && node.sourceFile != null) nodeSourceFiles.putIfAbsent(symbol, node.sourceFile);
            if (node.id != null && node.sourceFile != null) nodeSourceFiles.putIfAbsent(node.id, node.sourceFile);
        }
        try {
            Connection c = connection();
            boolean previous = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement nodes = c.prepareStatement(NODE_INSERT);
                 PreparedStatement edges = c.prepareStatement(EDGE_INSERT);
                 PreparedStatement annotations = c.prepareStatement(ANNOTATION_INSERT);
                 PreparedStatement annotationMeta = c.prepareStatement(ANNOTATION_META_INSERT);
                 PreparedStatement semantics = c.prepareStatement(SEMANTIC_INSERT);
                 PreparedStatement declarations = c.prepareStatement(DECLARATION_INSERT)) {
                for (Node node : result.nodes) bindNode(nodes, node, normalized);
                nodes.executeBatch();
                for (Edge edge : result.edges) bindEdge(edges, edge, normalized);
                edges.executeBatch();
                for (Annotation annotation : result.annotations) bindAnnotation(annotations, annotation, normalized);
                annotations.executeBatch();
                for (AnnotationMetaRelation relation : result.annotationMetaRelations) {
                    bindAnnotationMeta(annotationMeta, relation);
                }
                annotationMeta.executeBatch();
                for (SemanticAnnotation semantic : result.semanticAnnotations) {
                    bindSemantic(semantics, semantic,
                            semantic.sourceFile == null ? nodeSourceFiles.get(semantic.nodeId) : semantic.sourceFile,
                            normalized);
                }
                semantics.executeBatch();
                for (Declaration declaration : result.declarations) bindDeclaration(declarations, declaration);
                declarations.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(previous);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write staging batch", e);
        }
    }

    /** Resolve all cross-file references and prune invalid facts using bounded SQL operations. */
    public void finalizeRawFacts() {
        try {
            Connection c = connection();
            boolean previous = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                resolveReference(c, "stage_edges", "resolved_source", "source_ref", "1=1");
                resolveReference(c, "stage_edges", "resolved_target", "target_ref", "is_external=0");
                resolveReference(c, "stage_edges", "resolved_target", "external_target_fqn", "is_external=1");
                try (Statement statement = c.createStatement()) {
                    statement.executeUpdate("UPDATE stage_edges SET external_target_fqn=NULL,is_external=0,"
                            + "resolution=CASE WHEN relation='CALLS' AND confidence='"
                            + GraphConstants.Confidence.INFERRED + "' THEN NULL ELSE resolution END "
                            + "WHERE is_external=1 AND resolved_target IS NOT NULL");
                    statement.executeUpdate("UPDATE stage_edges SET external_target_fqn=target_ref,"
                            + "target_ref=NULL,is_external=1,confidence='" + GraphConstants.Confidence.AMBIGUOUS
                            + "' WHERE is_external=0 AND resolved_target IS NULL "
                            + "AND target_ref IS NOT NULL AND target_is_key=0");
                }
                bindRemainingExternalTargets(c);
                resolveReference(c, "stage_annotations", "resolved_node", "node_ref", "1=1");
                resolveReference(c, "stage_semantic_annotations", "resolved_node", "node_ref", "1=1");
                try (Statement statement = c.createStatement()) {
                    statement.executeUpdate("UPDATE stage_edges SET source_file=(SELECT n.source_file "
                            + "FROM stage_nodes n WHERE n.id=stage_edges.resolved_source) "
                            + "WHERE source_file IS NULL AND resolved_source IS NOT NULL");
                    statement.executeUpdate("UPDATE stage_annotations SET source_file=(SELECT n.source_file "
                            + "FROM stage_nodes n WHERE n.id=stage_annotations.resolved_node) "
                            + "WHERE source_file IS NULL AND resolved_node IS NOT NULL");
                    int before = scalarInt(c, "SELECT count(*) FROM stage_edges")
                            + scalarInt(c, "SELECT count(*) FROM stage_annotations");
                    statement.executeUpdate("DELETE FROM stage_edges WHERE resolved_source IS NULL "
                            + "OR (is_external=0 AND resolved_target IS NULL)");
                    statement.executeUpdate("DELETE FROM stage_annotations WHERE resolved_node IS NULL");
                    droppedDanglingFacts += before - scalarInt(c, "SELECT count(*) FROM stage_edges")
                            - scalarInt(c, "SELECT count(*) FROM stage_annotations");
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(previous);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to finalize staged graph", e);
        }
    }

    public PromotionStats promoteFull(SqliteStore target) {
        closeConnection();
        try {
            if (!target.schemaExists()) target.initSchema();
            Connection c = target.connection();
            attach(c);
            validateNodeOwnership(c);
            final int[] wired = {0};
            final long[] callSitePersistenceNanos = {0L};
            try {
                target.inTransaction(ignored -> {
                    try (Statement statement = c.createStatement()) {
                        clearGraph(statement);
                        insertAllFromStage(statement);
                        wired[0] = DatabaseWiringResolver.rebuild(c);
                        long persistenceStarted = System.nanoTime();
                        CallSitePersistence.rebuildFromStage(c, ALIAS);
                        callSitePersistenceNanos[0] = System.nanoTime() - persistenceStarted;
                        IndexRevision.bump(c);
                    }
                });
            } finally {
                detach(c);
            }
            return new PromotionStats(reboundExternalTargets, droppedDanglingFacts, wired[0],
                    callSitePersistenceNanos[0]);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to promote full staged graph", e);
        }
    }

    public IncrementalPromotionStats promoteIncremental(SqliteStore target,
                                                         List<String> affectedFiles,
                                                         boolean rebuildSpringXmlGraph,
                                                         boolean rebuildDerivedWiring) {
        return promoteIncremental(target, affectedFiles,
                rebuildSpringXmlGraph ? Set.of(ProducerIds.SPRING_XML) : Set.of(),
                rebuildDerivedWiring);
    }

    public IncrementalPromotionStats promoteIncremental(SqliteStore target,
                                                         List<String> affectedFiles,
                                                         Set<String> rebuiltProjectProducers,
                                                         boolean rebuildDerivedWiring) {
        closeConnection();
        try {
            Connection c = target.connection();
            attach(c);
            validateNodeOwnership(c);
            int oldNodeCount = countObsoleteNodes(c, affectedFiles);
            oldNodeCount += countObsoleteProjectNodes(c, rebuiltProjectProducers);
            int oldEdgeCount = countAffectedEdges(c, affectedFiles);
            int oldGenerated = rebuildDerivedWiring
                    ? scalarInt(c, "SELECT count(*) FROM edges WHERE " + generatedPredicate())
                    : 0;
            final int[] wired = {0};
            final long[] callSitePersistenceNanos = {0L};
            try {
                target.inTransaction(ignored -> {
                    try {
                        CallSitePersistence.AffectedScope affectedCallSites =
                                CallSitePersistence.captureAffected(
                                        c, affectedFiles, rebuiltProjectProducers);
                        replaceAffectedGraph(c, affectedFiles, rebuiltProjectProducers,
                                rebuildDerivedWiring);
                        if (rebuildDerivedWiring) wired[0] = DatabaseWiringResolver.rebuild(c);
                        long persistenceStarted = System.nanoTime();
                        CallSitePersistence.refreshFromStage(c, ALIAS, affectedCallSites);
                        callSitePersistenceNanos[0] = System.nanoTime() - persistenceStarted;
                        IndexRevision.bump(c);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            } finally {
                detach(c);
            }
            int newNodes = stagedCount("stage_nodes");
            int newEdges = stagedCount("stage_edges") + wired[0];
            return new IncrementalPromotionStats(oldNodeCount, oldEdgeCount + oldGenerated,
                    newNodes, newEdges, wired[0], callSitePersistenceNanos[0]);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to promote incremental staged graph", e);
        }
    }

    public Set<String> allSymbolIds() {
        Set<String> out = new HashSet<>();
        try (Statement statement = connection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT symbol_id FROM stage_nodes")) {
            while (rows.next()) out.add(rows.getString(1));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read staged symbols", e);
        }
        return out;
    }

    public List<SymbolFact> symbolFacts() {
        try (Statement statement = connection().createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id,symbol_id,kind,module,scope,source_file,metadata FROM stage_nodes")) {
            List<SymbolFact> out = new ArrayList<>();
            while (rows.next()) {
                out.add(new SymbolFact(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7)));
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read staged symbol facts", e);
        }
    }

    public List<TypeRelationFact> typeRelations() {
        String sql = "SELECT source_ref,target_ref,external_target_fqn,relation FROM stage_edges "
                + "WHERE relation IN ('INHERITS','IMPLEMENTS')";
        try (Statement statement = connection().createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<TypeRelationFact> out = new ArrayList<>();
            while (rows.next()) {
                String source = symbol(rows.getString(1));
                String target = rows.getString(2) != null ? symbol(rows.getString(2)) : rows.getString(3);
                if (source != null && target != null) {
                    out.add(new TypeRelationFact(source, target, rows.getString(4)));
                }
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read staged type relations", e);
        }
    }

    private static String symbol(String id) {
        return NodeKeyFactory.isKey(id) ? NodeKeyFactory.symbolId(id) : id;
    }

    public Map<String, BeanRefTarget> rawBeanTargets() {
        Map<String, BeanRefTarget> out = new HashMap<>();
        String sql = "SELECT symbol_id,metadata FROM stage_nodes WHERE kind='BEAN' ORDER BY seq";
        try (Statement statement = connection().createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                String bean = rows.getString(1);
                String type = beanClassFromMetadata(rows.getString(2));
                if (type == null) type = fallbackBeanClass(bean);
                out.put(bean, new BeanRefTarget(bean, type));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read staged bean targets", e);
        }
        return out;
    }

    private String fallbackBeanClass(String bean) throws SQLException {
        String sql = "SELECT COALESCE(target_ref,external_target_fqn) FROM stage_edges "
                + "WHERE relation='DEFINED_BY' AND source_ref=? ORDER BY seq";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, bean);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String target = rows.getString(1);
                    if (target != null && !target.contains("#")) return target;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String beanClassFromMetadata(String metadata) {
        if (metadata == null) return null;
        try {
            Object tree = com.anatomist.json.Json.parseTree(metadata);
            if (!(tree instanceof Map<?, ?> map)) return null;
            for (String key : List.of("productClass", "returnType", "className")) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    public Map<String, FileCacheService.SourceFileStats> sourceFileStats() {
        Map<String, int[]> values = new LinkedHashMap<>();
        accumulateCounts(values, "SELECT source_file,count(*) FROM stage_nodes GROUP BY source_file", true);
        accumulateCounts(values, "SELECT source_file,count(*) FROM stage_edges "
                + "WHERE source_file IS NOT NULL GROUP BY source_file", false);
        Map<String, FileCacheService.SourceFileStats> out = new LinkedHashMap<>();
        values.forEach((file, counts) -> out.put(file,
                new FileCacheService.SourceFileStats(counts[0], counts[1])));
        return out;
    }

    public int stagedNodeCount() { return stagedCount("stage_nodes"); }
    public int stagedEdgeCount() { return stagedCount("stage_edges"); }
    public int reboundExternalTargets() { return reboundExternalTargets; }
    public int droppedDanglingFacts() { return droppedDanglingFacts; }

    private void accumulateCounts(Map<String, int[]> values, String sql, boolean nodes) {
        try (Statement statement = connection().createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                int[] counts = values.computeIfAbsent(rows.getString(1), ignored -> new int[2]);
                counts[nodes ? 0 : 1] = rows.getInt(2);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count staged facts", e);
        }
    }

    private int stagedCount(String table) {
        try { return scalarInt(connection(), "SELECT count(*) FROM " + table); }
        catch (SQLException e) { throw new RuntimeException(e); }
    }

    private void replaceAffectedGraph(Connection c, List<String> files, Set<String> projectProducers,
                                      boolean rebuildDerivedWiring)
            throws SQLException {
        String placeholders = placeholders(files.size());
        if (!projectProducers.isEmpty()) {
            String producers = quoted(new ArrayList<>(projectProducers));
            try (Statement statement = c.createStatement()) {
                statement.executeUpdate("DELETE FROM semantic_annotations WHERE producer_id IN (" + producers + ")");
                statement.executeUpdate("DELETE FROM annotations WHERE producer_id IN (" + producers + ")");
                statement.executeUpdate("DELETE FROM annotation_meta_relations WHERE producer_id IN (" + producers + ")");
                statement.executeUpdate("DELETE FROM declarations WHERE producer_id IN (" + producers + ")");
                statement.executeUpdate("DELETE FROM edges WHERE producer_id IN (" + producers + ")");
            }
        }
        if (!files.isEmpty()) {
            try (PreparedStatement semantic = c.prepareStatement(
                        "DELETE FROM semantic_annotations WHERE node_id IN "
                                + "(SELECT id FROM nodes WHERE source_file IN (" + placeholders + "))");
                 PreparedStatement annotations = c.prepareStatement(
                        "DELETE FROM annotations WHERE node_id IN "
                                + "(SELECT id FROM nodes WHERE source_file IN (" + placeholders + "))");
                 PreparedStatement annotationMeta = c.prepareStatement(
                        "DELETE FROM annotation_meta_relations WHERE source_file IN (" + placeholders + ")");
                 PreparedStatement edges = c.prepareStatement(
                        "DELETE FROM edges WHERE (source_file IN (" + placeholders + ")"
                                + (rebuildDerivedWiring ? "" : " AND (metadata IS NULL OR NOT ("
                                + generatedPredicate() + "))")
                                + ") OR "
                                + "(source_file IS NULL AND source_id IN "
                                + "(SELECT id FROM nodes WHERE source_file IN (" + placeholders + ")))");
                 PreparedStatement cache = c.prepareStatement(
                        "DELETE FROM file_cache WHERE source_file IN (" + placeholders + ")");
                 PreparedStatement declarations = c.prepareStatement(
                        "DELETE FROM declarations WHERE source_file IN (" + placeholders + ")")) {
                bindFiles(semantic, files, 1);
                bindFiles(annotations, files, 1);
                bindFiles(annotationMeta, files, 1);
                bindFiles(edges, files, 2);
                bindFiles(cache, files, 1);
                bindFiles(declarations, files, 1);
                semantic.executeUpdate(); annotations.executeUpdate(); annotationMeta.executeUpdate();
                edges.executeUpdate(); cache.executeUpdate();
                declarations.executeUpdate();
            }
        }
        try (Statement statement = c.createStatement()) {
            insertNodesFromStage(statement);
            if (!files.isEmpty()) {
                statement.executeUpdate("DELETE FROM nodes WHERE source_file IN (" + quoted(files)
                        + ") AND id NOT IN (SELECT id FROM " + ALIAS + ".stage_nodes)");
            }
            if (!projectProducers.isEmpty()) {
                String producers = quoted(new ArrayList<>(projectProducers));
                statement.executeUpdate("DELETE FROM nodes AS n WHERE producer_id IN (" + producers + ") "
                        + "AND NOT EXISTS (SELECT 1 FROM " + ALIAS + ".stage_nodes s "
                        + "WHERE s.id=n.id AND s.producer_id=n.producer_id)");
            }
            insertFactsFromStage(statement);
        }
    }

    private int countObsoleteNodes(Connection c, List<String> files) throws SQLException {
        if (files.isEmpty()) return 0;
        return scalarInt(c, "SELECT count(*) FROM nodes n WHERE source_file IN (" + quoted(files)
                + ") AND NOT EXISTS (SELECT 1 FROM " + ALIAS + ".stage_nodes s WHERE s.id=n.id)");
    }

    private int countObsoleteProjectNodes(Connection c, Set<String> producers) throws SQLException {
        if (producers.isEmpty()) return 0;
        return scalarInt(c, "SELECT count(*) FROM nodes n WHERE producer_id IN ("
                + quoted(new ArrayList<>(producers)) + ") AND NOT EXISTS (SELECT 1 FROM " + ALIAS
                + ".stage_nodes s WHERE s.id=n.id AND s.producer_id=n.producer_id)");
    }

    private int countAffectedEdges(Connection c, List<String> files) throws SQLException {
        if (files.isEmpty()) return 0;
        String values = quoted(files);
        return scalarInt(c, "SELECT count(DISTINCT id) FROM edges WHERE source_file IN (" + values
                + ") OR source_id IN (SELECT id FROM nodes WHERE source_file IN (" + values + ")) "
                + "OR target_id IN (SELECT id FROM nodes WHERE source_file IN (" + values + "))");
    }

    private void bindRemainingExternalTargets(Connection c) throws SQLException {
        try (Statement statement = c.createStatement()) {
            reboundExternalTargets += statement.executeUpdate("UPDATE stage_edges SET "
                    + "resolved_target=external_target_fqn,external_target_fqn=NULL,is_external=0,"
                    + "resolution=CASE WHEN relation='CALLS' AND confidence='"
                    + GraphConstants.Confidence.INFERRED + "' THEN NULL ELSE resolution END "
                    + "WHERE is_external=1 AND EXISTS (SELECT 1 FROM stage_nodes n "
                    + "WHERE n.id=stage_edges.external_target_fqn)");
            reboundExternalTargets += statement.executeUpdate("UPDATE stage_edges SET "
                    + "resolved_target=(SELECT min(n.id) FROM stage_nodes n "
                    + "WHERE n.symbol_id=stage_edges.external_target_fqn),external_target_fqn=NULL,is_external=0,"
                    + "resolution=CASE WHEN relation='CALLS' AND confidence='"
                    + GraphConstants.Confidence.INFERRED + "' THEN NULL ELSE resolution END "
                    + "WHERE is_external=1 AND (SELECT count(*) FROM stage_nodes n "
                    + "WHERE n.symbol_id=stage_edges.external_target_fqn)=1");
            reboundExternalTargets += statement.executeUpdate("UPDATE stage_edges SET "
                    + "resolved_target=(SELECT min(n.id) FROM stage_nodes n "
                    + "WHERE n.symbol_id=replace(stage_edges.external_target_fqn,'$','.')),"
                    + "external_target_fqn=NULL,is_external=0,"
                    + "resolution=CASE WHEN relation='CALLS' AND confidence='"
                    + GraphConstants.Confidence.INFERRED + "' THEN NULL ELSE resolution END "
                    + "WHERE is_external=1 AND instr(external_target_fqn,'$')>0 "
                    + "AND metadata LIKE '%\"via\":\"reflection\"%' "
                    + "AND (SELECT count(*) FROM stage_nodes n "
                    + "WHERE n.symbol_id=replace(stage_edges.external_target_fqn,'$','.'))=1");
            reboundExternalTargets += statement.executeUpdate("UPDATE stage_edges SET "
                    + "resolved_target=(SELECT min(n.id) FROM stage_nodes n "
                    + "WHERE n.arity_key=stage_edges.target_arity_key),external_target_fqn=NULL,is_external=0,"
                    + "resolution=CASE WHEN relation='CALLS' AND confidence='"
                    + GraphConstants.Confidence.INFERRED + "' THEN NULL ELSE resolution END "
                    + "WHERE is_external=1 AND target_arity_key IS NOT NULL AND "
                    + "(SELECT count(*) FROM stage_nodes n "
                    + "WHERE n.arity_key=stage_edges.target_arity_key)=1");
        }
    }

    private static void resolveReference(Connection c, String table, String output,
                                         String reference, String additional) throws SQLException {
        String[] candidatePredicates = {
                "n.source_file=t.source_file",
                "n.module=t.source_module AND n.scope=t.source_scope",
                "n.module=t.source_module AND n.scope='MAIN'",
                "n.scope='MAIN'",
                "1=1"
        };
        try (Statement statement = c.createStatement()) {
            for (String candidate : candidatePredicates) {
                String matches = "n.symbol_id=t." + reference + " AND " + candidate;
                statement.executeUpdate("UPDATE " + table + " AS t SET " + output
                        + "=(SELECT min(n.id) FROM stage_nodes n WHERE " + matches + ") "
                        + "WHERE " + output + " IS NULL AND t." + reference + " IS NOT NULL AND "
                        + additional + " AND (SELECT count(*) FROM stage_nodes n WHERE " + matches + ")=1");
            }
        }
    }

    private void bindNode(PreparedStatement statement, Node node, boolean normalized) throws SQLException {
        String symbol = symbolOf(node);
        SourceIdentity identity = normalized
                ? NodeKeyFactory.identity(node.id) : identities.resolve(node.sourceFile);
        String id = normalized ? node.id : NodeKeyFactory.key(identity, symbol);
        int i = 1;
        statement.setString(i++, id); statement.setString(i++, symbol);
        statement.setString(i++, node.label); statement.setString(i++, node.kind);
        statement.setString(i++, node.qualifiedName); statement.setString(i++, node.pkg);
        statement.setString(i++, node.sourceFile == null ? "" : node.sourceFile);
        statement.setString(i++, node.sourceLocation);
        setNullableInt(statement, i++, node.beginLine); setNullableInt(statement, i++, node.beginColumn);
        setNullableInt(statement, i++, node.endLine); setNullableInt(statement, i++, node.endColumn);
        setNullableInt(statement, i++, node.sourceOrdinal); statement.setString(i++, identity.module());
        statement.setString(i++, identity.scope().name()); statement.setString(i++, node.javadoc);
        statement.setString(i++, node.metadata); statement.setString(i++, methodArityKey(symbol));
        statement.setString(i++, producer(node.producerId, ProducerIds.JAVA_CORE));
        statement.addBatch();
    }

    private void bindEdge(PreparedStatement statement, Edge edge, boolean normalized) throws SQLException {
        SourceIdentity identity = identities.resolve(edge.sourceFile);
        com.anatomist.model.EdgeTarget target = edge.target();
        String internalTarget = switch (target) {
            case com.anatomist.model.EdgeTarget.Internal internal -> internal.nodeId();
            case com.anatomist.model.EdgeTarget.External ignored -> null;
        };
        String externalTarget = switch (target) {
            case com.anatomist.model.EdgeTarget.Internal ignored -> null;
            case com.anatomist.model.EdgeTarget.External external -> external.fqn();
        };
        String resolution = switch (target) {
            case com.anatomist.model.EdgeTarget.Internal ignored -> null;
            case com.anatomist.model.EdgeTarget.External external -> external.resolution();
        };
        boolean external = target instanceof com.anatomist.model.EdgeTarget.External;
        boolean sourceKey = normalized || NodeKeyFactory.isKey(edge.sourceId);
        boolean targetKey = normalized || NodeKeyFactory.isKey(internalTarget);
        int i = 1;
        statement.setString(i++, edge.sourceId); statement.setString(i++, internalTarget);
        statement.setString(i++, externalTarget); statement.setString(i++, edge.relation);
        statement.setString(i++, edge.callKind); statement.setString(i++, edge.confidence == null
                ? GraphConstants.Confidence.EXTRACTED : edge.confidence);
        statement.setString(i++, resolution);
        statement.setString(i++, edge.context); statement.setInt(i++, external ? 1 : 0);
        statement.setString(i++, edge.sourceFile); statement.setString(i++, edge.sourceLocation);
        setNullableInt(statement, i++, edge.beginLine);
        setNullableInt(statement, i++, edge.beginColumn);
        setNullableInt(statement, i++, edge.endLine);
        setNullableInt(statement, i++, edge.endColumn);
        setNullableInt(statement, i++, edge.sourceOrdinal);
        statement.setString(i++, edge.syntaxTarget);
        statement.setString(i++, edge.receiverStaticType);
        statement.setString(i++, edge.metadata); statement.setString(i++, identity.module());
        statement.setString(i++, identity.scope().name()); statement.setInt(i++, sourceKey ? 1 : 0);
        statement.setInt(i++, targetKey ? 1 : 0);
        statement.setString(i++, sourceKey ? edge.sourceId : null);
        statement.setString(i++, !external && targetKey ? internalTarget : null);
        statement.setString(i++, methodArityKey(externalTarget));
        statement.setString(i++, producer(edge.producerId, ProducerIds.JAVA_CORE));
        statement.addBatch();
    }

    private void bindAnnotation(PreparedStatement statement, Annotation annotation, boolean normalized)
            throws SQLException {
        SourceIdentity identity = identities.resolve(annotation.sourceFile);
        boolean key = normalized || NodeKeyFactory.isKey(annotation.nodeId);
        int i = 1;
        statement.setString(i++, annotation.nodeId);
        statement.setString(i++, annotation.annotationFqn);
        statement.setString(i++, annotation.rawName == null ? annotation.annotationFqn : annotation.rawName);
        statement.setString(i++, annotation.attributes);
        statement.setString(i++, annotation.targetKind == null ? "entity" : annotation.targetKind);
        statement.setString(i++, annotation.targetPath);
        statement.setString(i++, annotation.language == null ? "java" : annotation.language);
        statement.setString(i++, annotation.mechanism == null ? "java.annotation" : annotation.mechanism);
        statement.setString(i++, annotation.resolutionStatus == null ? "exact" : annotation.resolutionStatus);
        statement.setString(i++, annotation.sourceFile);
        statement.setString(i++, annotation.sourceLocation);
        setNullableInt(statement, i++, annotation.beginLine);
        setNullableInt(statement, i++, annotation.beginColumn);
        setNullableInt(statement, i++, annotation.endLine);
        setNullableInt(statement, i++, annotation.endColumn);
        statement.setString(i++, identity.module()); statement.setString(i++, identity.scope().name());
        statement.setInt(i++, key ? 1 : 0); statement.setString(i++, key ? annotation.nodeId : null);
        statement.setString(i, producer(annotation.producerId, ProducerIds.JAVA_CORE));
        statement.addBatch();
    }

    private static void bindAnnotationMeta(PreparedStatement statement,
                                           AnnotationMetaRelation relation) throws SQLException {
        int i = 1;
        statement.setString(i++, relation.annotationFqn);
        statement.setString(i++, relation.metaAnnotationFqn);
        statement.setString(i++, relation.rawName == null
                ? relation.metaAnnotationFqn : relation.rawName);
        statement.setString(i++, relation.language == null ? "java" : relation.language);
        statement.setString(i++, relation.mechanism == null
                ? "java.annotation.meta" : relation.mechanism);
        statement.setString(i++, relation.resolutionStatus == null
                ? "exact" : relation.resolutionStatus);
        statement.setString(i++, relation.sourceFile);
        statement.setString(i++, relation.sourceLocation);
        statement.setString(i, producer(relation.producerId, ProducerIds.JAVA_CORE));
        statement.addBatch();
    }

    private void bindSemantic(PreparedStatement statement, SemanticAnnotation semantic,
                              String sourceFile, boolean normalized) throws SQLException {
        SourceIdentity identity = identities.resolve(sourceFile);
        boolean key = normalized || NodeKeyFactory.isKey(semantic.nodeId);
        int i = 1;
        statement.setString(i++, semantic.nodeId); statement.setInt(i++, semantic.docId == null ? 0 : semantic.docId);
        statement.setString(i++, semantic.category); statement.setString(i++, semantic.businessLabel);
        statement.setString(i++, semantic.businessDescription); statement.setString(i++, semantic.domainContext);
        statement.setString(i++, semantic.source); statement.setString(i++, semantic.confidence);
        statement.setString(i++, sourceFile); statement.setString(i++, identity.module());
        statement.setString(i++, identity.scope().name()); statement.setInt(i++, key ? 1 : 0);
        statement.setString(i++, key ? semantic.nodeId : null);
        statement.setString(i++, producer(semantic.producerId, ProducerIds.SEMANTIC_JAVADOC));
        statement.addBatch();
    }

    private void bindDeclaration(PreparedStatement statement, Declaration declaration)
            throws SQLException {
        SourceIdentity identity = identities.resolve(declaration.sourceFile);
        int i = 1;
        statement.setString(i++, declaration.symbolId);
        statement.setString(i++, declaration.qualifiedName);
        statement.setString(i++, declaration.label);
        statement.setString(i++, declaration.kind);
        statement.setString(i++, declaration.declarationKind);
        statement.setString(i++, declaration.typeKind);
        statement.setString(i++, declaration.visibility);
        statement.setString(i++, com.anatomist.json.Json.writeCompact(declaration.modifiers));
        statement.setString(i++, com.anatomist.json.Json.writeCompact(declaration.declaredModifiers));
        statement.setString(i++, com.anatomist.json.Json.writeCompact(declaration.implicitModifiers));
        statement.setString(i++, declaration.declaringType);
        statement.setString(i++, declaration.sourceFile);
        statement.setString(i++, declaration.sourceLocation);
        setNullableInt(statement, i++, declaration.beginLine);
        setNullableInt(statement, i++, declaration.beginColumn);
        setNullableInt(statement, i++, declaration.endLine);
        setNullableInt(statement, i++, declaration.endColumn);
        statement.setString(i++, identity.module());
        statement.setString(i++, identity.scope().name());
        statement.setInt(i++, declaration.nestingDepth);
        statement.setInt(i++, declaration.directMember ? 1 : 0);
        statement.setInt(i++, declaration.synthetic ? 1 : 0);
        statement.setInt(i++, declaration.bindingResolved ? 1 : 0);
        statement.setString(i, producer(declaration.producerId, ProducerIds.JAVA_CORE));
        statement.addBatch();
    }

    private static String symbolOf(Node node) {
        if (node.symbolId != null) return node.symbolId;
        return NodeKeyFactory.isKey(node.id) ? NodeKeyFactory.symbolId(node.id) : node.id;
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=OFF");
                statement.execute("PRAGMA synchronous=OFF");
                statement.execute("PRAGMA temp_store=FILE");
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.execute("PRAGMA cache_size=-64000");
            }
        }
        return connection;
    }

    private static void initSchema(Connection c) throws SQLException {
        try (Statement statement = c.createStatement()) {
            for (String sql : STAGING_SCHEMA) statement.execute(sql);
        }
    }

    private void attach(Connection c) throws SQLException {
        try (Statement statement = c.createStatement()) {
            statement.execute("ATTACH DATABASE '" + path.toString().replace("'", "''") + "' AS " + ALIAS);
        }
    }

    private static void detach(Connection c) throws SQLException {
        try (Statement statement = c.createStatement()) { statement.execute("DETACH DATABASE " + ALIAS); }
    }

    private static void clearGraph(Statement statement) throws SQLException {
        statement.executeUpdate("DELETE FROM declarations");
        statement.executeUpdate("DELETE FROM semantic_annotations");
        statement.executeUpdate("DELETE FROM annotation_meta_relations");
        statement.executeUpdate("DELETE FROM annotations");
        statement.executeUpdate("DELETE FROM call_site_targets");
        statement.executeUpdate("DELETE FROM call_sites");
        statement.executeUpdate("DELETE FROM call_site_owners");
        statement.executeUpdate("DELETE FROM edges");
        statement.executeUpdate("DELETE FROM nodes");
        statement.executeUpdate("DELETE FROM file_cache");
        statement.executeUpdate("DELETE FROM file_dependencies");
        statement.executeUpdate("DELETE FROM index_diagnostics");
        statement.executeUpdate("DELETE FROM project_meta");
    }

    private static void insertAllFromStage(Statement statement) throws SQLException {
        insertNodesFromStage(statement);
        insertFactsFromStage(statement);
    }

    private static void insertNodesFromStage(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO nodes(id,symbol_id,label,kind,qualified_name,package,source_file,"
                + "source_location,begin_line,begin_column,end_line,end_column,source_ordinal,module,scope,javadoc,metadata,producer_id) SELECT id,symbol_id,label,kind,qualified_name,"
                + "package,source_file,source_location,begin_line,begin_column,end_line,end_column,source_ordinal,module,scope,javadoc,metadata,producer_id FROM " + ALIAS
                + ".stage_nodes ORDER BY seq ON CONFLICT(id) DO UPDATE SET symbol_id=excluded.symbol_id,"
                + "label=excluded.label,kind=excluded.kind,qualified_name=excluded.qualified_name,"
                + "package=excluded.package,source_file=excluded.source_file,source_location=excluded.source_location,"
                + "begin_line=excluded.begin_line,begin_column=excluded.begin_column,end_line=excluded.end_line,"
                + "end_column=excluded.end_column,source_ordinal=excluded.source_ordinal,"
                + "module=excluded.module,scope=excluded.scope,javadoc=excluded.javadoc,metadata=excluded.metadata,"
                + "producer_id=excluded.producer_id");
    }

    private static void insertFactsFromStage(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT OR REPLACE INTO declarations(symbol_id,qualified_name,label,kind,"
                + "declaration_kind,type_kind,visibility,modifiers,declared_modifiers,implicit_modifiers,"
                + "declaring_type,source_file,source_location,begin_line,begin_column,end_line,end_column,"
                + "module,scope,nesting_depth,direct_member,synthetic,"
                + "binding_resolved,producer_id) SELECT symbol_id,qualified_name,label,kind,declaration_kind,type_kind,visibility,"
                + "modifiers,declared_modifiers,implicit_modifiers,declaring_type,source_file,source_location,"
                + "begin_line,begin_column,end_line,end_column,module,"
                + "scope,nesting_depth,direct_member,synthetic,binding_resolved,producer_id FROM " + ALIAS
                + ".stage_declarations ORDER BY seq");
        statement.executeUpdate("INSERT INTO edges(source_id,target_id,external_target_fqn,relation,call_kind,"
                + "confidence,resolution,context,is_external,source_file,source_location,begin_line,begin_column,"
                + "end_line,end_column,source_ordinal,syntax_target,receiver_static_type,metadata,producer_id) SELECT resolved_source,"
                + "resolved_target,external_target_fqn,relation,call_kind,confidence,resolution,context,is_external,source_file,"
                + "source_location,begin_line,begin_column,end_line,end_column,source_ordinal,syntax_target,"
                + "receiver_static_type,metadata,producer_id FROM " + ALIAS
                + ".stage_edges WHERE relation<>'" + GraphConstants.Relation.CALLS + "' ORDER BY seq");
        statement.executeUpdate("INSERT INTO annotations(node_id,annotation_fqn,raw_name,attributes,target_kind,"
                + "target_path,language,mechanism,resolution_status,source_file,source_location,begin_line,"
                + "begin_column,end_line,end_column,producer_id) SELECT resolved_node,annotation_fqn,raw_name,"
                + "attributes,target_kind,target_path,language,mechanism,resolution_status,source_file,"
                + "source_location,begin_line,begin_column,end_line,end_column,producer_id FROM " + ALIAS
                + ".stage_annotations ORDER BY seq");
        statement.executeUpdate("INSERT OR IGNORE INTO annotation_meta_relations(annotation_fqn,"
                + "meta_annotation_fqn,raw_name,language,mechanism,resolution_status,source_file,"
                + "source_location,producer_id) SELECT annotation_fqn,meta_annotation_fqn,raw_name,language,"
                + "mechanism,resolution_status,source_file,source_location,producer_id FROM " + ALIAS
                + ".stage_annotation_meta ORDER BY seq");
        statement.executeUpdate("INSERT INTO semantic_annotations(node_id,doc_id,category,business_label,"
                + "business_description,domain_context,source,confidence,source_file,producer_id) SELECT resolved_node,NULLIF(doc_id,0),"
                + "category,business_label,business_description,domain_context,source,confidence,source_file,producer_id FROM " + ALIAS
                + ".stage_semantic_annotations WHERE resolved_node IS NOT NULL ORDER BY seq");
    }

    private static void validateNodeOwnership(Connection connection) throws SQLException {
        String sql = "SELECT n.id,n.producer_id,s.producer_id FROM nodes n "
                + "JOIN " + ALIAS + ".stage_nodes s ON s.id=n.id "
                + "WHERE n.producer_id<>s.producer_id LIMIT 1";
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            if (row.next()) {
                throw new SQLException("EXTENSION_NODE_OWNERSHIP_CONFLICT: node " + row.getString(1)
                        + " is owned by " + row.getString(2) + ", not " + row.getString(3));
            }
        }
    }

    private static int scalarInt(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
    private static void bindFiles(PreparedStatement statement, List<String> files, int repetitions) throws SQLException {
        int index = 1;
        for (int r = 0; r < repetitions; r++) for (String file : files) statement.setString(index++, file);
    }
    private static String quoted(List<String> values) {
        return values.stream().map(value -> "'" + value.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(","));
    }
    private static String generatedPredicate() {
        return "producer_id='" + ProducerIds.DERIVED_WIRING + "'";
    }

    private static String producer(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String methodArityKey(String methodId) {
        if (methodId == null) return null;
        int open = methodId.indexOf('(');
        int hash = methodId.lastIndexOf('#', open >= 0 ? open : methodId.length());
        int close = methodId.lastIndexOf(')');
        if (hash < 0 || open < hash || close < open) return null;
        String params = methodId.substring(open + 1, close).trim();
        int arity = params.isEmpty() ? 0 : params.split(",", -1).length;
        return methodId.substring(0, open) + "/" + arity;
    }

    private void closeConnection() {
        if (connection == null) return;
        try { connection.close(); } catch (SQLException ignored) {}
        connection = null;
    }

    private void reset() throws SQLException {
        reboundExternalTargets = 0;
        droppedDanglingFacts = 0;
        try (Statement statement = connection().createStatement()) {
            statement.executeUpdate("DELETE FROM stage_semantic_annotations");
            statement.executeUpdate("DELETE FROM stage_declarations");
            statement.executeUpdate("DELETE FROM stage_annotations");
            statement.executeUpdate("DELETE FROM stage_annotation_meta");
            statement.executeUpdate("DELETE FROM stage_edges");
            statement.executeUpdate("DELETE FROM stage_nodes");
            statement.executeUpdate("DELETE FROM sqlite_sequence WHERE name LIKE 'stage_%'");
        }
    }

    @Override public void close() {
        closeConnection();
        if (!reusable) try { Files.deleteIfExists(path); } catch (Exception ignored) {}
    }

    public record PromotionStats(int reboundExternalTargets, int droppedDanglingFacts, int wiredEdges,
                                 long callSitePersistenceNanos) {}
    public record IncrementalPromotionStats(int deletedNodes, int deletedEdges,
                                            int writtenNodes, int writtenEdges, int wiredEdges,
                                            long callSitePersistenceNanos) {}

    private static final String NODE_INSERT = "INSERT INTO stage_nodes(id,symbol_id,label,kind,qualified_name,"
            + "package,source_file,source_location,begin_line,begin_column,end_line,end_column,source_ordinal,module,scope,javadoc,metadata,arity_key,producer_id) VALUES "
            + "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET symbol_id=excluded.symbol_id,"
            + "label=excluded.label,kind=excluded.kind,qualified_name=excluded.qualified_name,package=excluded.package,"
            + "source_file=excluded.source_file,source_location=excluded.source_location,begin_line=excluded.begin_line,"
            + "begin_column=excluded.begin_column,end_line=excluded.end_line,end_column=excluded.end_column,source_ordinal=excluded.source_ordinal,module=excluded.module,"
            + "scope=excluded.scope,javadoc=excluded.javadoc,metadata=excluded.metadata,arity_key=excluded.arity_key,"
            + "producer_id=excluded.producer_id";
    private static final String EDGE_INSERT = "INSERT INTO stage_edges(source_ref,target_ref,external_target_fqn,"
            + "relation,call_kind,confidence,resolution,context,is_external,source_file,source_location,"
            + "begin_line,begin_column,end_line,end_column,source_ordinal,syntax_target,receiver_static_type,metadata,source_module,"
            + "source_scope,source_is_key,target_is_key,resolved_source,resolved_target,target_arity_key,producer_id) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String ANNOTATION_INSERT = "INSERT INTO stage_annotations(node_ref,annotation_fqn,raw_name,"
            + "attributes,target_kind,target_path,language,mechanism,resolution_status,source_file,source_location,"
            + "begin_line,begin_column,end_line,end_column,source_module,source_scope,node_is_key,resolved_node,producer_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String ANNOTATION_META_INSERT = "INSERT INTO stage_annotation_meta(annotation_fqn,"
            + "meta_annotation_fqn,raw_name,language,mechanism,resolution_status,source_file,source_location,producer_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?)";
    private static final String SEMANTIC_INSERT = "INSERT INTO stage_semantic_annotations(node_ref,doc_id,category,"
            + "business_label,business_description,domain_context,source,confidence,source_file,source_module,"
            + "source_scope,node_is_key,resolved_node,producer_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String DECLARATION_INSERT = "INSERT OR REPLACE INTO stage_declarations(symbol_id,"
            + "qualified_name,label,kind,declaration_kind,type_kind,visibility,modifiers,declared_modifiers,"
            + "implicit_modifiers,declaring_type,source_file,source_location,begin_line,begin_column,end_line,end_column,"
            + "module,scope,nesting_depth,direct_member,synthetic,binding_resolved,producer_id) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final List<String> STAGING_SCHEMA = List.of(
            "CREATE TABLE stage_nodes(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,symbol_id TEXT,"
                    + "label TEXT,kind TEXT,qualified_name TEXT,package TEXT,source_file TEXT,source_location TEXT,"
                    + "begin_line INTEGER,begin_column INTEGER,end_line INTEGER,end_column INTEGER,source_ordinal INTEGER,"
                    + "module TEXT,scope TEXT,javadoc TEXT,metadata TEXT,arity_key TEXT,producer_id TEXT NOT NULL)",
            "CREATE TRIGGER stage_node_owner_conflict BEFORE UPDATE ON stage_nodes "
                    + "WHEN old.producer_id<>new.producer_id BEGIN SELECT RAISE(ABORT,'EXTENSION_NODE_OWNERSHIP_CONFLICT'); END",
            "CREATE INDEX stage_nodes_symbol ON stage_nodes(symbol_id)",
            "CREATE INDEX stage_nodes_candidate ON stage_nodes(symbol_id,source_file,module,scope)",
            "CREATE INDEX stage_nodes_arity ON stage_nodes(arity_key)",
            "CREATE TABLE stage_declarations(seq INTEGER PRIMARY KEY AUTOINCREMENT,symbol_id TEXT NOT NULL,"
                    + "qualified_name TEXT NOT NULL,label TEXT NOT NULL,kind TEXT NOT NULL,declaration_kind TEXT NOT NULL,"
                    + "type_kind TEXT,visibility TEXT NOT NULL,modifiers TEXT NOT NULL,declared_modifiers TEXT NOT NULL,"
                    + "implicit_modifiers TEXT NOT NULL,declaring_type TEXT,source_file TEXT NOT NULL,source_location TEXT,"
                    + "begin_line INTEGER,begin_column INTEGER,end_line INTEGER,end_column INTEGER,"
                    + "module TEXT NOT NULL,scope TEXT NOT NULL,nesting_depth INTEGER NOT NULL,direct_member INTEGER NOT NULL,"
                    + "synthetic INTEGER NOT NULL,binding_resolved INTEGER NOT NULL,producer_id TEXT NOT NULL,"
                    + "UNIQUE(symbol_id,module,scope,source_file,producer_id))",
            "CREATE TABLE stage_edges(seq INTEGER PRIMARY KEY AUTOINCREMENT,source_ref TEXT,target_ref TEXT,"
                    + "external_target_fqn TEXT,relation TEXT,call_kind TEXT,confidence TEXT,resolution TEXT,context TEXT,"
                    + "is_external INTEGER,source_file TEXT,source_location TEXT,begin_line INTEGER,begin_column INTEGER,"
                    + "end_line INTEGER,end_column INTEGER,source_ordinal INTEGER,syntax_target TEXT,receiver_static_type TEXT,"
                    + "metadata TEXT,source_module TEXT,"
                    + "source_scope TEXT,source_is_key INTEGER,target_is_key INTEGER,resolved_source TEXT,"
                    + "resolved_target TEXT,target_arity_key TEXT,producer_id TEXT NOT NULL)",
            "CREATE INDEX stage_edges_refs ON stage_edges(source_ref,target_ref,external_target_fqn)",
            "CREATE TABLE stage_annotations(seq INTEGER PRIMARY KEY AUTOINCREMENT,node_ref TEXT,annotation_fqn TEXT,"
                    + "raw_name TEXT NOT NULL,attributes TEXT,target_kind TEXT NOT NULL,target_path TEXT,language TEXT NOT NULL,"
                    + "mechanism TEXT NOT NULL,resolution_status TEXT NOT NULL,source_file TEXT,source_location TEXT,"
                    + "begin_line INTEGER,begin_column INTEGER,end_line INTEGER,end_column INTEGER,source_module TEXT,"
                    + "source_scope TEXT,node_is_key INTEGER,resolved_node TEXT,producer_id TEXT NOT NULL)",
            "CREATE INDEX stage_annotations_ref ON stage_annotations(node_ref)",
            "CREATE TABLE stage_annotation_meta(seq INTEGER PRIMARY KEY AUTOINCREMENT,annotation_fqn TEXT NOT NULL,"
                    + "meta_annotation_fqn TEXT,raw_name TEXT NOT NULL,language TEXT NOT NULL,mechanism TEXT NOT NULL,"
                    + "resolution_status TEXT NOT NULL,source_file TEXT,source_location TEXT,producer_id TEXT NOT NULL)",
            "CREATE TABLE stage_semantic_annotations(seq INTEGER PRIMARY KEY AUTOINCREMENT,node_ref TEXT,doc_id INTEGER,"
                    + "category TEXT,business_label TEXT,business_description TEXT,domain_context TEXT,source TEXT,"
                    + "confidence TEXT,source_file TEXT,source_module TEXT,source_scope TEXT,node_is_key INTEGER,"
                    + "resolved_node TEXT,producer_id TEXT NOT NULL)",
            "CREATE INDEX stage_semantic_ref ON stage_semantic_annotations(node_ref)"
    );

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER);
        else statement.setInt(index, value);
    }
}
