package com.anatomist.query;

import com.anatomist.store.IndexLock;
import com.anatomist.store.IndexSchema;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Read-only query API over a previously-built anatomist SQLite index.
 *
 * <p>Thin facade that delegates to focused service classes:
 * {@link SearchService}, {@link TypeContextService}, {@link CallGraphService},
 * {@link DependencyService}, {@link OverviewService}, {@link EnrichmentService}.</p>
 *
 * <p>Thread-safety: each instance owns one {@link Connection}; not safe for
 * concurrent use. Construct one per query invocation.</p>
 */
public class QueryService implements AutoCloseable {

    public static final int MAX_DEPTH = CallGraphService.MAX_DEPTH;

    private final IndexLock lock;
    private final Connection conn;
    private final NodeResolver resolver;

    private final SearchService search;
    private final TypeContextService typeContext;
    private final CallGraphService callGraph;
    private final DependencyService dependency;
    private final OverviewService overview;
    private final EnrichmentService enrichment;
    private final SourceWindowService sourceWindows;
    private final BranchSliceService branchSlices;

    public Connection connection() { return conn; }

    public void selectNodes(String module, String scope) {
        resolver.select(module, scope);
    }

    public QueryService(Path dbPath) {
        this.lock = IndexLock.forRead(dbPath);
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (java.sql.Statement st = conn.createStatement();
                 java.sql.ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                int actual = rs.next() ? rs.getInt(1) : 0;
                if (actual != IndexSchema.VERSION) {
                    conn.close();
                    lock.close();
                    throw new IllegalStateException("SCHEMA_MISMATCH: index schema " + actual
                            + ", required " + IndexSchema.VERSION + "; re-index required");
                }
            }
        } catch (SQLException e) {
            lock.close();
            throw new RuntimeException("Failed to open index db: " + dbPath, e);
        }
        this.resolver = new NodeResolver(conn);
        this.callGraph = new CallGraphService(conn, resolver);
        this.search = new SearchService(conn, resolver);
        this.typeContext = new TypeContextService(conn, resolver, callGraph);
        this.dependency = new DependencyService(conn, resolver);
        this.overview = new OverviewService(conn, resolver);
        this.enrichment = new EnrichmentService(conn, resolver, typeContext, overview);
        this.sourceWindows = new SourceWindowService(conn);
        this.branchSlices = new BranchSliceService(conn, resolver, callGraph, sourceWindows);
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
        lock.close();
    }

    // ── Search ───────────────────────────────────────────────────────────

    public List<NodeRow> search(String term, String kind, int limit) {
        return search.search(term, kind, limit);
    }

    public List<NodeRow> search(String term, String kind, int limit, int offset) {
        return search.search(term, kind, limit, offset);
    }

    public List<NodeRow> searchByName(String glob, String kind, int limit) {
        return search.searchByName(glob, kind, limit);
    }

    public List<NodeRow> searchByName(String glob, String kind, int limit, int offset) {
        return search.searchByName(glob, kind, limit, offset);
    }

    public int countByName(String glob, String kind) {
        return search.countByName(glob, kind);
    }

    public int countSearch(String term, String kind) {
        return search.countSearch(term, kind);
    }

    public List<NodeRow> searchByAnnotation(String annotationTerm, String kind, int limit) {
        return search.searchByAnnotation(annotationTerm, kind, limit);
    }

    public List<NodeRow> searchByAnnotation(String annotationTerm, String kind, int limit, int offset) {
        return search.searchByAnnotation(annotationTerm, kind, limit, offset);
    }

    public int countByAnnotation(String annotationTerm, String kind) {
        return search.countByAnnotation(annotationTerm, kind);
    }

    public List<NodeRow> implementorsOf(String typeRef) {
        return search.implementorsOf(typeRef);
    }

    public List<NodeRow> implementorsOf(String typeRef, boolean recursive) {
        return search.implementorsOf(typeRef, recursive);
    }

    public int countImplementorsOf(String typeRef, boolean recursive) {
        return search.countImplementorsOf(typeRef, recursive);
    }

    // ── Context ──────────────────────────────────────────────────────────

    public ContextResult context(String fqnOrShorthand, int withCalleesDepth) {
        return typeContext.context(fqnOrShorthand, withCalleesDepth);
    }

    public HierarchyResult hierarchy(String typeRef) {
        return typeContext.hierarchy(typeRef);
    }

    // ── Call Graph ───────────────────────────────────────────────────────

    public List<EdgeRow> calleesOf(String methodRef, int depth) {
        return callGraph.calleesOf(methodRef, depth);
    }

    public List<EdgeRow> calleesOf(String methodRef, int depth, boolean throughCallbacks) {
        return callGraph.calleesOf(methodRef, depth, throughCallbacks);
    }

    public List<EdgeRow> callersOf(String methodRef, int depth) {
        return callGraph.callersOf(methodRef, depth);
    }

    public List<EdgeRow> callersOf(String methodRef, int depth, boolean throughCallbacks) {
        return callGraph.callersOf(methodRef, depth, throughCallbacks);
    }

    public List<EdgeRow> callPath(String fromMethodRef, String toMethodRef, int maxDepth) {
        return callGraph.callPath(fromMethodRef, toMethodRef, maxDepth);
    }

    public List<EdgeRow> callPath(String fromMethodRef, String toMethodRef,
                                  int maxDepth, boolean throughCallbacks) {
        return callGraph.callPath(fromMethodRef, toMethodRef, maxDepth, throughCallbacks);
    }

    public void attachSourceWindows(List<EdgeRow> rows, int contextLines) {
        sourceWindows.attachToEdges(rows, contextLines);
    }

    public List<BranchSlice> branchesOf(String methodRef,
                                        int depth,
                                        boolean throughCallbacks,
                                        Integer sourceWindowLines) {
        return branchSlices.branchesOf(methodRef, depth, throughCallbacks, sourceWindowLines);
    }

    // ── Dependencies ────────────────────────────────────────────────────

    public List<EdgeRow> depsOf(String typeRef) {
        return dependency.depsOf(typeRef);
    }

    public PagedResult<EdgeRow> depsOfPaged(String typeRef, int limit, int offset, String filter) {
        return dependency.depsOfPaged(typeRef, limit, offset, filter);
    }

    public List<EdgeRow> usedBy(String typeRef) {
        return dependency.usedBy(typeRef);
    }

    public PagedResult<EdgeRow> usedByPaged(String typeRef, int limit, int offset, String filter) {
        return dependency.usedByPaged(typeRef, limit, offset, filter);
    }

    public List<EdgeRow> fieldReaders(String fieldRef) {
        return dependency.fieldReaders(fieldRef);
    }

    public List<EdgeRow> fieldWriters(String fieldRef) {
        return dependency.fieldWriters(fieldRef);
    }

    public PagedResult<EdgeRow> fieldAccessPaged(String fieldRef, String mode, int limit, int offset, String filter) {
        return dependency.fieldAccessPaged(fieldRef, mode, limit, offset, filter);
    }

    // ── Overview ────────────────────────────────────────────────────────

    public List<Map<String, Object>> packageDeps() {
        return overview.packageDeps();
    }

    public OverviewResult overview() {
        return overview.overview();
    }

    public List<ClassEdge> classDepsInternal(int maxEdges) {
        return overview.classDepsInternal(maxEdges);
    }

    // ── Enrich ──────────────────────────────────────────────────────────

    public EnrichResult enrichNode(String fqnOrShorthand, int depth, boolean withDocs) {
        return enrichment.enrichNode(fqnOrShorthand, depth, withDocs);
    }

    public EnrichResult enrichPackage(String pkg, boolean withDocs) {
        return enrichment.enrichPackage(pkg, withDocs);
    }

    public List<SemanticAnnotationRow> readSemanticAnnotations(String nodeId) {
        NodeRow node = resolver.resolveNodeRow(nodeId);
        return enrichment.readSemanticAnnotations(node == null ? nodeId : node.id);
    }

    public List<DocSnippet> searchRelatedDocs(String label, String qualifiedName) {
        return enrichment.searchRelatedDocs(label, qualifiedName);
    }

    public List<String> suggestQueries(EnrichResult r) {
        return enrichment.suggestQueries(r);
    }

    // ── Resolution (delegated to NodeResolver) ──────────────────────────

    public List<String> resolveTypeIds(String input) {
        return resolver.resolveTypeIds(input);
    }

    public List<String> resolveMethodIds(String input) {
        return resolver.resolveMethodIds(input);
    }

    public NodeRow resolveNodeRow(String input) {
        return resolver.resolveNodeRow(input);
    }

    public List<NodeRow> resolveNodeRows(String input) {
        return resolver.resolveNodeRows(input);
    }
}
