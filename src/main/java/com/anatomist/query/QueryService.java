package com.anatomist.query;

import com.anatomist.store.IndexLock;
import com.anatomist.store.IndexSchema;
import com.anatomist.core.GraphSemantics;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import com.anatomist.query.semantic.SemanticCursor;

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
    private final CallSiteService callSites;
    private final DependencyService dependency;
    private final OverviewService overview;
    private final EnrichmentService enrichment;
    private final SourceContextService sourceContext;
    private final SourceWindowService sourceWindows;
    private final BranchSliceService branchSlices;
    private final JavaSemanticService javaSemantics;
    private final GenericSemanticService genericSemantics;

    public Connection connection() { return conn; }

    public void selectNodes(String module, String scope) {
        resolver.select(module, scope);
        javaSemantics.select(module, scope);
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
            try (java.sql.PreparedStatement st = conn.prepareStatement(
                    "SELECT value FROM project_meta WHERE key=?")) {
                st.setString(1, GraphSemantics.META_KEY);
                try (java.sql.ResultSet rs = st.executeQuery()) {
                    int actual = rs.next() ? GraphSemantics.parse(rs.getString(1)) : 0;
                    if (actual != GraphSemantics.VERSION) {
                        conn.close();
                        lock.close();
                        throw new IllegalStateException("GRAPH_SEMANTICS_MISMATCH: index graph semantics "
                                + actual + ", required " + GraphSemantics.VERSION
                                + "; re-index with --recreate");
                    }
                }
            }
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("PRAGMA query_only=ON");
            }
        } catch (SQLException e) {
            lock.close();
            throw new RuntimeException("Failed to open index db: " + dbPath, e);
        }
        this.resolver = new NodeResolver(conn);
        this.callGraph = new CallGraphService(conn, resolver);
        this.callSites = new CallSiteService(conn, resolver);
        this.search = new SearchService(conn, resolver);
        this.typeContext = new TypeContextService(conn, resolver, callGraph);
        this.dependency = new DependencyService(conn, resolver);
        this.overview = new OverviewService(conn, resolver);
        this.enrichment = new EnrichmentService(conn, resolver, typeContext, overview);
        this.sourceContext = new SourceContextService(conn, dbPath);
        this.sourceWindows = new SourceWindowService(conn);
        this.branchSlices = new BranchSliceService(conn, resolver, callGraph, sourceWindows);
        this.javaSemantics = new JavaSemanticService(conn, resolver);
        this.genericSemantics = new GenericSemanticService(conn, resolver);
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

    public SemanticCursor<NodeRow> semanticSearchCursor(SearchService.SemanticMode mode,
                                                         String selector, String kind,
                                                         int limit, int offset) {
        return search.semanticCursor(mode, selector, kind, limit, offset);
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

    public ContextResult context(String fqnOrShorthand, int withCalleesDepth, SourceRequest sourceRequest) {
        ContextResult result = typeContext.context(fqnOrShorthand, withCalleesDepth);
        if (result != null && sourceRequest != null) {
            result.source = sourceContext.read(result.node, sourceRequest);
        }
        return result;
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

    public TraversalResult<EdgeRow> calleesTraversal(String methodRef, int depth,
                                                      boolean throughCallbacks) {
        return callGraph.calleesTraversal(methodRef, depth, throughCallbacks);
    }

    public List<EdgeRow> callersOf(String methodRef, int depth) {
        return callGraph.callersOf(methodRef, depth);
    }

    public List<EdgeRow> callersOf(String methodRef, int depth, boolean throughCallbacks) {
        return callGraph.callersOf(methodRef, depth, throughCallbacks);
    }

    public TraversalResult<EdgeRow> callersTraversal(String methodRef, int depth,
                                                      boolean throughCallbacks) {
        return callGraph.callersTraversal(methodRef, depth, throughCallbacks);
    }

    public List<EdgeRow> directCalls(String methodRef, String direction) {
        return callGraph.directCalls(methodRef, direction);
    }

    public List<CallSiteRow> callSites(String methodRef, String direction) {
        return callSites.calls(methodRef, direction);
    }

    public List<JavaSemanticRows.TypeRelation> typeRelations(String typeId, String direction,
                                                              String semantic, boolean transitive,
                                                              int maxDepth, int limit) {
        return javaSemantics.typeRelations(typeId, direction, semantic, transitive, maxDepth, limit);
    }

    public List<JavaSemanticRows.RuntimeImplementation> runtimeImplementations(
            String typeId, String instantiability, String world, int maxDepth, int limit) {
        return javaSemantics.runtimeImplementations(typeId, instantiability, world, maxDepth, limit);
    }

    public List<JavaSemanticRows.CallableRelation> callableRelations(
            String callableId, String direction, boolean transitive, int maxDepth, int limit) {
        return javaSemantics.callableRelations(callableId, direction, transitive, maxDepth, limit);
    }

    public List<JavaSemanticRows.DispatchTarget> dispatch(Map<String, Object> callSite,
                                                           String algorithm, String world,
                                                           int maxDepth, int limit) {
        return javaSemantics.dispatch(callSite, algorithm, world, maxDepth, limit);
    }

    public List<NodeRow> semanticMembers(String containerId, boolean recursive,
                                         int maxDepth, int limit) {
        return genericSemantics.members(containerId, recursive, maxDepth, limit);
    }

    public List<EdgeRow> semanticBindings(String entityId, String direction,
                                          String semantic, int limit) {
        return genericSemantics.bindings(entityId, direction, semantic, limit);
    }

    public List<GenericSemanticRows.Site> semanticSites(String entityId, String direction,
                                                         java.util.Set<String> relations,
                                                         int limit) {
        return genericSemantics.sites(entityId, direction, relations, limit);
    }

    public List<GenericSemanticRows.Site> resolvedCallPath(String startId, String endSelector,
                                                            int maxDepth) {
        return genericSemantics.resolvedCallPath(startId, endSelector, maxDepth);
    }

    public List<GenericSemanticRows.Site> semanticCallPath(String startId, String endSelector,
                                                            int maxDepth, String dispatch) {
        if ("resolved".equals(dispatch)) return resolvedCallPath(startId, endSelector, maxDepth);
        String end = resolver.resolveMethod(endSelector).requireUnique().id;
        Deque<String> frontier = new ArrayDeque<>(); frontier.add(startId);
        Set<String> visited = new HashSet<>(); visited.add(startId);
        Map<String, String> parents = new HashMap<>();
        Map<String, GenericSemanticRows.Site> hops = new HashMap<>();
        int depth = 0;
        while (!frontier.isEmpty() && depth++ < maxDepth && !visited.contains(end)) {
            int width = frontier.size();
            while (width-- > 0 && !visited.contains(end)) {
                String current = frontier.removeFirst();
                for (CallSiteRow site : callSites.calls(current, "outgoing")) {
                    Map<String, Object> raw = new LinkedHashMap<>();
                    raw.put("id", site.id); raw.put("caller", site.callerId);
                    raw.put("dispatch_kind", site.dispatchKind == null ? "unknown"
                            : site.dispatchKind.toLowerCase(java.util.Locale.ROOT));
                    raw.put("resolved_targets", site.targets.stream().map(target -> {
                        Map<String, Object> value = new LinkedHashMap<>();
                        value.put("id", target.id()); value.put("external", target.external());
                        value.put("resolution_status", target.resolutionStatus());
                        if (target.qualifiedName() != null) value.put("qualified_name", target.qualifiedName());
                        return value;
                    }).toList());
                    for (JavaSemanticRows.DispatchTarget candidate : javaSemantics.dispatch(
                            raw, "auto", "workspace-open", maxDepth, 10_000)) {
                        String next = candidate.target();
                        if (next == null || !visited.add(next)) continue;
                        parents.put(next, current);
                        hops.put(next, new GenericSemanticRows.Site(current, next, null, "CALLS",
                                site.sourceFile, site.beginLine, site.beginColumn, site.endLine,
                                site.endColumn, site.ordinal, site.context, site.producerId,
                                candidate.resolutionStatus(), null));
                        frontier.addLast(next);
                        if (next.equals(end)) break;
                    }
                }
            }
        }
        if (!visited.contains(end)) return List.of();
        List<GenericSemanticRows.Site> result = new ArrayList<>();
        for (String current = end; !current.equals(startId); current = parents.get(current)) {
            GenericSemanticRows.Site hop = hops.get(current);
            if (hop == null) return List.of();
            result.add(hop);
        }
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    public SemanticCursor<CallSiteRow> callSitesCursor(String methodRef, String direction) {
        return callSites.cursor(methodRef, direction);
    }

    public List<EdgeRow> callPath(String fromMethodRef, String toMethodRef, int maxDepth) {
        return callGraph.callPath(fromMethodRef, toMethodRef, maxDepth);
    }

    public List<EdgeRow> callPath(String fromMethodRef, String toMethodRef,
                                  int maxDepth, boolean throughCallbacks) {
        return callGraph.callPath(fromMethodRef, toMethodRef, maxDepth, throughCallbacks);
    }

    public TraversalResult<EdgeRow> callPathTraversal(String fromMethodRef, String toMethodRef,
                                                       int maxDepth, boolean throughCallbacks) {
        return callGraph.callPathTraversal(fromMethodRef, toMethodRef, maxDepth, throughCallbacks);
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

    public TraversalResult<BranchSlice> branchesTraversal(String methodRef,
                                                           int depth,
                                                           boolean throughCallbacks,
                                                           Integer sourceWindowLines) {
        return branchSlices.branchesTraversal(
                methodRef, depth, throughCallbacks, sourceWindowLines);
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

    public SourceContext source(String nodeRef, SourceRequest request) {
        NodeRow node = resolver.resolveNode(nodeRef).requireUnique();
        return sourceContext.read(node, request);
    }

    public SourceContext sourceRange(String sourceFile, int beginLine, int beginColumn,
                                     int endLine, int endColumn, SourceRequest request) {
        return sourceContext.readRange(sourceFile, beginLine, beginColumn,
                endLine, endColumn, request);
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

    public SymbolResolution resolveNode(String input) {
        return resolver.resolveNode(input);
    }

    public SymbolResolution resolveType(String input) {
        return resolver.resolveType(input);
    }

    public SymbolResolution resolveMethod(String input) {
        return resolver.resolveMethod(input);
    }

    public SymbolResolution resolveField(String input) {
        return resolver.resolveField(input);
    }
}
