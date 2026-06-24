package com.anatomist.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.anatomist.query.QueryInfra.*;

public class EnrichmentService {

    private static final Set<String> TYPE_KINDS = Set.of(
            "CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD", "ANONYMOUS_CLASS");

    private final Connection conn;
    private final NodeResolver resolver;
    private final TypeContextService typeContext;
    private final OverviewService overview;

    public EnrichmentService(Connection conn, NodeResolver resolver,
                             TypeContextService typeContext, OverviewService overview) {
        this.conn = conn;
        this.resolver = resolver;
        this.typeContext = typeContext;
        this.overview = overview;
    }

    public EnrichResult enrichNode(String fqnOrShorthand, int depth, boolean withDocs) {
        NodeRow node = resolver.resolveNodeRow(fqnOrShorthand);
        if (node == null) return null;

        EnrichResult r = new EnrichResult();
        r.node = node;

        int effectiveDepth = Math.max(0, depth);
        ContextResult ctx = typeContext.context(fqnOrShorthand, effectiveDepth);
        if (ctx != null) {
            r.members = ctx.members;
            r.annotations = ctx.annotations;
            if (ctx.callees != null) r.callees = ctx.callees;
        }

        r.semanticAnnotations = readSemanticAnnotations(node.id);
        if (withDocs) {
            r.relatedDocs = searchRelatedDocs(node.label, node.qualifiedName);
        }
        r.suggestedQueries = suggestQueries(r);
        return r;
    }

    public EnrichResult enrichPackage(String pkg, boolean withDocs) {
        if (pkg == null || pkg.isEmpty()) return null;

        List<NodeRow> types = runNodeQuery(conn,
                "SELECT " + RowMappers.NODE_COLS
              + "  FROM nodes n WHERE package = ? AND kind IN ("
              + qmarks(TYPE_KINDS.size()) + ") "
              + " ORDER BY label",
                concat(List.of(pkg), new ArrayList<>(TYPE_KINDS)));
        if (types.isEmpty()) return null;

        EnrichResult r = new EnrichResult();
        r.pkg = pkg;
        r.members = types;

        for (NodeRow t : types) {
            r.semanticAnnotations.addAll(readSemanticAnnotations(t.id));
        }

        for (Map<String, Object> row : overview.packageDeps()) {
            if (pkg.equals(row.get("source_package"))) {
                r.packageDeps.add(row);
            }
        }

        if (withDocs) {
            r.relatedDocs = searchRelatedDocs(shortName(pkg), pkg);
        }
        r.suggestedQueries = suggestQueries(r);
        return r;
    }

    public List<SemanticAnnotationRow> readSemanticAnnotations(String nodeId) {
        if (nodeId == null) return new ArrayList<>();
        List<SemanticAnnotationRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT category, business_label, business_description, domain_context, source, confidence"
              + " FROM semantic_annotations WHERE node_id = ? "
              + " ORDER BY category, source")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SemanticAnnotationRow row = new SemanticAnnotationRow();
                    row.category = rs.getString(1);
                    row.businessLabel = rs.getString(2);
                    row.businessDescription = rs.getString(3);
                    row.domainContext = rs.getString(4);
                    row.source = rs.getString(5);
                    row.confidence = rs.getString(6);
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return out;
    }

    public List<DocSnippet> searchRelatedDocs(String label, String qualifiedName) {
        List<DocSnippet> out = new ArrayList<>();
        if ((label == null || label.isEmpty()) && (qualifiedName == null || qualifiedName.isEmpty())) {
            return out;
        }
        String term = label != null && !label.isEmpty() ? label : qualifiedName;
        if (term != null && term.contains(".")) term = shortName(term);
        if (term == null || term.isEmpty()) return out;

        String sql = "SELECT d.path, d.title, d.content, d.doc_type "
                + " FROM doc_content dc JOIN documents d ON dc.rowid = d.id "
                + " WHERE doc_content MATCH ? ORDER BY rank LIMIT 3";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, term);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DocSnippet snip = new DocSnippet();
                    snip.path = rs.getString(1);
                    snip.title = rs.getString(2);
                    String content = rs.getString(3);
                    snip.snippet = content == null ? null
                            : (content.length() > 200 ? content.substring(0, 200) + "…" : content);
                    snip.docType = rs.getString(4);
                    out.add(snip);
                }
            }
        } catch (SQLException e) {
            return out;
        }
        return out;
    }

    public List<String> suggestQueries(EnrichResult r) {
        List<String> out = new ArrayList<>();
        if (r == null) return out;
        if (r.pkg != null) {
            out.add("anatomist package-deps --index <db>");
            out.add("anatomist search <term> --kind CLASS --index <db>");
            return out;
        }
        if (r.node == null) return out;
        String q = r.node.qualifiedName;
        String kind = r.node.kind;
        if ("METHOD".equals(kind) || "CONSTRUCTOR".equals(kind)) {
            out.add("anatomist callers-of " + q);
            out.add("anatomist callees-of " + q + " --depth 3");
        } else if (TYPE_KINDS.contains(kind)) {
            out.add("anatomist callers-of " + q);
            out.add("anatomist callees-of " + q + " --depth 2");
            out.add("anatomist deps-of " + q);
            out.add("anatomist used-by " + q);
            out.add("anatomist hierarchy " + q);
        }
        return out;
    }

    private static List<Object> concat(List<?> a, List<?> b) {
        List<Object> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }
}
