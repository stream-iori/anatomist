package com.anatomist.query;

import com.anatomist.json.JsonCodec;
import com.anatomist.json.JsonCodecRegistry;
import com.anatomist.model.SemanticAnnotation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Hand-written codecs for every JSON-bearing DTO in the project.
 *
 *  <p>Each codec produces a {@link LinkedHashMap} tree mirroring the historic
 *  Jackson output: snake_case keys, field order matching the Java field order,
 *  null fields suppressed for types that previously bore {@code @JsonInclude(NON_NULL)}.
 *  {@link HierarchyResult.Entry} is the documented exception — its nulls must
 *  pass through (every golden file depends on this).
 *
 *  <p>Adding a new serialisable DTO means: (1) create the class, (2) register
 *  a codec here, (3) add a test in {@code DtoCodecsTest}. No reflection. */
public final class DtoCodecs {

    private static volatile boolean registered;

    private DtoCodecs() {}

    /** Idempotent. Called from {@code JsonFormatter} static init so the
     *  query commands implicitly register the full DTO set on first use. */
    public static synchronized void ensureRegistered() {
        if (registered) return;
        registerAll();
        registered = true;
    }

    private static void registerAll() {
        JsonCodecRegistry.register(NodeRow.class, NODE_ROW);
        JsonCodecRegistry.register(EdgeRow.class, EDGE_ROW);
        JsonCodecRegistry.register(SourceWindow.class, SOURCE_WINDOW);
        JsonCodecRegistry.register(HierarchyResult.Entry.class, HIERARCHY_ENTRY);
        JsonCodecRegistry.register(HierarchyResult.class, HIERARCHY);
        JsonCodecRegistry.register(ContextResult.class, CONTEXT);
        JsonCodecRegistry.register(DocSnippet.class, DOC_SNIPPET);
        JsonCodecRegistry.register(SemanticAnnotationRow.class, SEMANTIC_ANNOTATION_ROW);
        JsonCodecRegistry.register(EnrichResult.class, ENRICH);
        JsonCodecRegistry.register(QueryEnvelope.class, QUERY_ENVELOPE);
        JsonCodecRegistry.register(SemanticAnnotation.class, SEMANTIC_ANNOTATION);
        JsonCodecRegistry.register(BranchSlice.class, BRANCH_SLICE);
        JsonCodecRegistry.register(PackageStat.class, PACKAGE_STAT);
        JsonCodecRegistry.register(BlockResult.class, BLOCK_RESULT);
        JsonCodecRegistry.register(SliceResult.class, SLICE_RESULT);
        JsonCodecRegistry.register(OverviewResult.class, OVERVIEW);
    }

    // ── helpers ──

    private static Map<String, Object> obj() { return new LinkedHashMap<>(); }
    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }
    private static void putNullable(Map<String, Object> m, String k, Object v) {
        m.put(k, v); // emit even when null (parity with Jackson on classes without @JsonInclude)
    }

    // ── DTO codecs ──

    private static final JsonCodec<NodeRow> NODE_ROW = new JsonCodec<>() {
        @Override public Object toTree(NodeRow n) {
            Map<String, Object> m = obj();
            put(m, "id", n.id);
            put(m, "symbol_id", n.symbolId);
            put(m, "label", n.label);
            put(m, "kind", n.kind);
            put(m, "qualified_name", n.qualifiedName);
            put(m, "source_file", n.sourceFile);
            put(m, "source_location", n.sourceLocation);
            put(m, "module", n.module);
            put(m, "scope", n.scope);
            putNullable(m, "javadoc", n.javadoc);
            return m;
        }
        @Override public NodeRow fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<EdgeRow> EDGE_ROW = new JsonCodec<>() {
        @Override public Object toTree(EdgeRow e) {
            Map<String, Object> m = obj();
            put(m, "source", e.source);
            put(m, "source_label", e.sourceLabel);
            put(m, "source_symbol_id", e.sourceSymbolId);
            put(m, "source_module", e.sourceModule);
            put(m, "source_scope", e.sourceScope);
            put(m, "target", e.target);
            put(m, "target_label", e.targetLabel);
            put(m, "target_qualified_name", e.targetQualifiedName);
            put(m, "target_symbol_id", e.targetSymbolId);
            put(m, "target_module", e.targetModule);
            put(m, "target_scope", e.targetScope);
            put(m, "external_target_fqn", e.externalTargetFqn);
            put(m, "relation", e.relation);
            put(m, "call_kind", e.callKind);
            put(m, "confidence", e.confidence);
            put(m, "is_external", e.isExternal);
            put(m, "depth", e.depth);
            put(m, "source_file", e.sourceFile);
            put(m, "source_location", e.sourceLocation);
            put(m, "source_window", e.sourceWindow);
            put(m, "context", e.context);
            put(m, "metadata", e.metadata);
            put(m, "via", e.via);
            return m;
        }
        @Override public EdgeRow fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<SourceWindow> SOURCE_WINDOW = new JsonCodec<>() {
        @Override public Object toTree(SourceWindow s) {
            Map<String, Object> m = obj();
            put(m, "path", s.path);
            put(m, "line", s.line);
            put(m, "start_line", s.startLine);
            put(m, "end_line", s.endLine);
            put(m, "snippet", s.snippet);
            return m;
        }
        @Override public SourceWindow fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<HierarchyResult.Entry> HIERARCHY_ENTRY = new JsonCodec<>() {
        @Override public Object toTree(HierarchyResult.Entry e) {
            Map<String, Object> m = obj();
            // @JsonInclude NOT applied — null fields ARE emitted (golden-file contract).
            putNullable(m, "id", e.id);
            putNullable(m, "label", e.label);
            putNullable(m, "qualified_name", e.qualifiedName);
            putNullable(m, "role", e.role);
            putNullable(m, "depth", e.depth);
            putNullable(m, "is_external", e.isExternal);
            putNullable(m, "external_target_fqn", e.externalTargetFqn);
            return m;
        }
        @Override public HierarchyResult.Entry fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<HierarchyResult> HIERARCHY = new JsonCodec<>() {
        @Override public Object toTree(HierarchyResult h) {
            Map<String, Object> m = obj();
            put(m, "extends_chain", h.extendsChain);
            put(m, "implements_list", h.implementsList);
            return m;
        }
        @Override public HierarchyResult fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<ContextResult> CONTEXT = new JsonCodec<>() {
        @Override public Object toTree(ContextResult r) {
            Map<String, Object> m = obj();
            put(m, "node", r.node);
            put(m, "members", r.members);
            put(m, "annotations", r.annotations);
            put(m, "framework", r.framework);
            put(m, "callees", r.callees);
            return m;
        }
        @Override public ContextResult fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<DocSnippet> DOC_SNIPPET = new JsonCodec<>() {
        @Override public Object toTree(DocSnippet d) {
            Map<String, Object> m = obj();
            put(m, "title", d.title);
            put(m, "path", d.path);
            put(m, "snippet", d.snippet);
            put(m, "doc_type", d.docType);
            return m;
        }
        @Override public DocSnippet fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<SemanticAnnotationRow> SEMANTIC_ANNOTATION_ROW = new JsonCodec<>() {
        @Override public Object toTree(SemanticAnnotationRow s) {
            Map<String, Object> m = obj();
            put(m, "category", s.category);
            put(m, "business_label", s.businessLabel);
            put(m, "business_description", s.businessDescription);
            put(m, "domain_context", s.domainContext);
            put(m, "source", s.source);
            put(m, "confidence", s.confidence);
            return m;
        }
        @Override public SemanticAnnotationRow fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<EnrichResult> ENRICH = new JsonCodec<>() {
        @Override public Object toTree(EnrichResult r) {
            Map<String, Object> m = obj();
            put(m, "node", r.node);
            put(m, "pkg", r.pkg);
            put(m, "members", r.members);
            put(m, "annotations", r.annotations);
            put(m, "semantic_annotations", r.semanticAnnotations);
            put(m, "callees", r.callees);
            put(m, "package_deps", r.packageDeps);
            put(m, "related_docs", r.relatedDocs);
            put(m, "suggested_queries", r.suggestedQueries);
            return m;
        }
        @Override public EnrichResult fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<QueryEnvelope> QUERY_ENVELOPE = new JsonCodec<>() {
        @Override public Object toTree(QueryEnvelope env) {
            Map<String, Object> m = obj();
            put(m, "query", env.query);
            put(m, "results", env.results);
            put(m, "stats", env.stats);
            if (!env.budget.isEmpty()) put(m, "budget", env.budget);
            if (!env.evidence.isEmpty()) put(m, "evidence", env.evidence);
            put(m, "next_queries", env.nextQueries);
            put(m, "blocks", env.blocks);
            return m;
        }
        @Override public QueryEnvelope fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<BranchSlice> BRANCH_SLICE = new JsonCodec<>() {
        @Override public Object toTree(BranchSlice b) {
            Map<String, Object> m = obj();
            put(m, "owner", b.owner);
            put(m, "owner_label", b.ownerLabel);
            put(m, "context", b.context);
            put(m, "branch_kind", b.branchKind);
            put(m, "branch_line", b.branchLine);
            put(m, "source_file", b.sourceFile);
            put(m, "source_window", b.sourceWindow);
            put(m, "calls", b.calls);
            put(m, "reads", b.reads);
            put(m, "writes", b.writes);
            return m;
        }
        @Override public BranchSlice fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<PackageStat> PACKAGE_STAT = new JsonCodec<>() {
        @Override public Object toTree(PackageStat p) {
            Map<String, Object> m = obj();
            put(m, "name", p.name);
            put(m, "types", p.types);
            put(m, "methods", p.methods);
            return m;
        }
        @Override public PackageStat fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<OverviewResult> OVERVIEW = new JsonCodec<>() {
        @Override public Object toTree(OverviewResult r) {
            Map<String, Object> m = obj();
            put(m, "kind_counts", r.kindCounts);
            put(m, "internal_edge_counts", r.internalEdgeCounts);
            put(m, "external_edge_counts", r.externalEdgeCounts);
            put(m, "packages", r.packages);
            put(m, "package_deps", r.packageDeps);
            return m;
        }
        @Override public OverviewResult fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<SemanticAnnotation> SEMANTIC_ANNOTATION = new JsonCodec<>() {        @Override public Object toTree(SemanticAnnotation sa) {
            Map<String, Object> m = obj();
            put(m, "node_id", sa.nodeId);
            put(m, "doc_id", sa.docId);
            put(m, "category", sa.category);
            put(m, "business_label", sa.businessLabel);
            put(m, "business_description", sa.businessDescription);
            put(m, "domain_context", sa.domainContext);
            put(m, "source", sa.source);
            put(m, "confidence", sa.confidence);
            return m;
        }
        @Override @SuppressWarnings("unchecked")
        public SemanticAnnotation fromTree(Object tree) {
            return SemanticAnnotation.fromJson((Map<String, Object>) tree);
        }
    };

    private static final JsonCodec<BlockResult> BLOCK_RESULT = new JsonCodec<>() {
        @Override public Object toTree(BlockResult b) {
            Map<String, Object> m = obj();
            put(m, "name", b.name);
            put(m, "methods", b.methods);
            put(m, "owning_types", new java.util.ArrayList<>(b.owningTypes));
            put(m, "internal_edges", b.internalEdges);
            put(m, "inbound_edges", b.inboundEdges);
            put(m, "outbound_edges", b.outboundEdges);
            put(m, "fields_read", b.fieldsRead);
            put(m, "fields_written", b.fieldsWritten);
            put(m, "annotations", b.annotations);
            put(m, "depth_range", java.util.List.of(b.depthRange[0], b.depthRange[1]));
            put(m, "control_flow_context", b.controlFlowContext);
            putNullable(m, "javadoc_summary", b.javadocSummary);
            return m;
        }
        @Override public BlockResult fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };

    private static final JsonCodec<SliceResult> SLICE_RESULT = new JsonCodec<>() {
        @Override public Object toTree(SliceResult s) {
            Map<String, Object> m = obj();
            put(m, "level", s.level);
            put(m, "blocks", s.blocks);
            return m;
        }
        @Override public SliceResult fromTree(Object tree) { throw new UnsupportedOperationException(); }
    };
}
