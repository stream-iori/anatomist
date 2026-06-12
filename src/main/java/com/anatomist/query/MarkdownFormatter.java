package com.anatomist.query;

import java.util.List;
import java.util.Map;

/** Render {@link EnrichResult} as markdown for direct Agent / human consumption.
 *  Output is line-bounded (≤ ~200 lines) by truncating long callee lists. */
public final class MarkdownFormatter {

    /** Hard cap on rendered lines per design BR-002; truncation appends a note. */
    public static final int MAX_LINES = 200;

    private MarkdownFormatter() {}

    public static String format(EnrichResult r) {
        if (r == null) return "";
        if (r.pkg != null) return formatPackage(r);
        return formatNode(r);
    }

    /** Render the {@code overview} command's top-down summary. */
    public static String format(OverviewResult ov) {
        if (ov == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("# Project Overview\n\n");

        sb.append("## Node Kinds\n\n");
        if (ov.kindCounts.isEmpty()) sb.append("_None._\n\n");
        else {
            sb.append("| Kind | Count |\n|---|---|\n");
            for (Map.Entry<String, Long> e : ov.kindCounts.entrySet()) {
                sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
            }
            sb.append('\n');
        }

        if (!ov.archRoleCounts.isEmpty()) {
            sb.append("## Architecture Roles\n\n");
            sb.append("| Role | Count |\n|---|---|\n");
            for (Map.Entry<String, Long> e : ov.archRoleCounts.entrySet()) {
                sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## Edges\n\n");
        sb.append("| Relation | Internal | External |\n|---|---|---|\n");
        java.util.LinkedHashSet<String> rels = new java.util.LinkedHashSet<>();
        rels.addAll(ov.internalEdgeCounts.keySet());
        rels.addAll(ov.externalEdgeCounts.keySet());
        for (String rel : rels) {
            sb.append("| ").append(rel)
              .append(" | ").append(ov.internalEdgeCounts.getOrDefault(rel, 0L))
              .append(" | ").append(ov.externalEdgeCounts.getOrDefault(rel, 0L))
              .append(" |\n");
        }
        sb.append('\n');

        sb.append("## Packages (").append(ov.packages.size()).append(")\n\n");
        if (ov.packages.isEmpty()) sb.append("_None._\n\n");
        else {
            sb.append("| Package | Types | Methods |\n|---|---|---|\n");
            for (PackageStat p : ov.packages) {
                sb.append("| `").append(p.name).append("` | ")
                  .append(p.types).append(" | ").append(p.methods).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## Package Dependencies\n\n");
        if (ov.packageDeps.isEmpty()) sb.append("_None._\n");
        else {
            sb.append("| Source | Target | Relation | Count |\n|---|---|---|---|\n");
            for (Map<String, Object> d : ov.packageDeps) {
                sb.append("| `").append(d.get("source_package"))
                  .append("` | `").append(d.get("target_package"))
                  .append("` | ").append(d.get("relation"))
                  .append(" | ").append(d.get("edge_count"))
                  .append(" |\n");
            }
        }
        return sb.toString();
    }

    private static String formatNode(EnrichResult r) {
        StringBuilder sb = new StringBuilder();
        NodeRow n = r.node;
        sb.append("# ").append(n.label).append(" (").append(n.kind).append(")\n\n");

        sb.append("| Field | Value |\n|---|---|\n");
        sb.append("| qualified_name | `").append(n.qualifiedName).append("` |\n");
        if (n.sourceFile != null) sb.append("| source_file | `").append(n.sourceFile).append("` |\n");
        if (n.sourceLocation != null) sb.append("| source_location | ").append(n.sourceLocation).append(" |\n");
        if (n.module != null) sb.append("| module | ").append(n.module).append(" |\n");
        if (n.javadoc != null) sb.append("| javadoc | ").append(n.javadoc).append(" |\n");
        if (r.archRole != null) sb.append("| arch_role | **").append(r.archRole.role)
                .append("** (").append(r.archRole.confidence).append(") |\n");
        sb.append('\n');

        // Members split into fields/methods/other.
        List<NodeRow> fields = r.members.stream().filter(m -> "FIELD".equals(m.kind)).toList();
        List<NodeRow> methods = r.members.stream()
                .filter(m -> "METHOD".equals(m.kind) || "CONSTRUCTOR".equals(m.kind)).toList();
        if (!fields.isEmpty()) {
            sb.append("## Fields\n\n");
            for (NodeRow m : fields) sb.append("- `").append(m.label).append("`\n");
            sb.append('\n');
        }
        if (!methods.isEmpty()) {
            sb.append("## Methods\n\n");
            for (NodeRow m : methods) sb.append("- `").append(m.label).append("` — `")
                    .append(m.qualifiedName).append("`\n");
            sb.append('\n');
        }

        // Annotations (Java-level @Override / @Service / ...).
        if (!r.annotations.isEmpty()) {
            sb.append("## Annotations\n\n");
            for (Map<String, Object> ann : r.annotations) {
                Object fqn = ann.get("annotation_fqn");
                sb.append("- `@").append(fqn).append("`\n");
            }
            sb.append('\n');
        }

        // Semantic annotations table.
        sb.append("## Semantic Annotations\n\n");
        if (r.semanticAnnotations.isEmpty()) {
            sb.append("_None recorded._\n\n");
        } else {
            sb.append("| Category | Label | Source | Confidence |\n|---|---|---|---|\n");
            for (SemanticAnnotationRow s : r.semanticAnnotations) {
                sb.append("| ").append(nullSafe(s.category))
                  .append(" | ").append(nullSafe(s.businessLabel))
                  .append(" | ").append(nullSafe(s.source))
                  .append(" | ").append(nullSafe(s.confidence))
                  .append(" |\n");
            }
            sb.append('\n');
        }

        // Call graph (callees). Apply MAX_LINES budget guard.
        sb.append("## Call Graph\n\n");
        int budget = MAX_LINES - sb.toString().split("\n", -1).length
                - 12 /* tail (docs + suggestions) */;
        if (budget < 5) budget = 5;
        int written = 0;
        boolean truncated = false;
        for (EdgeRow e : r.callees) {
            if (written >= budget) { truncated = true; break; }
            String tgt = e.target != null ? e.target
                    : (e.externalTargetFqn != null ? e.externalTargetFqn : "?");
            sb.append("- depth=").append(e.depth == null ? 1 : e.depth)
              .append(" `").append(nullSafe(e.source))
              .append("` → `").append(tgt).append("`");
            if (e.context != null) sb.append(" _[").append(e.context).append("]_");
            sb.append("\n");
            written++;
        }
        if (r.callees.isEmpty()) sb.append("_No callees._\n");
        if (truncated) {
            sb.append("\n_… truncated, use `anatomist callees-of ")
              .append(n.qualifiedName).append(" --depth N` for more._\n");
        }
        sb.append('\n');

        // Related docs.
        if (!r.relatedDocs.isEmpty()) {
            sb.append("## Related Documentation\n\n");
            for (DocSnippet d : r.relatedDocs) {
                sb.append("- **").append(nullSafe(d.title)).append("** (`")
                  .append(nullSafe(d.path)).append("`)\n");
                if (d.snippet != null) sb.append("  > ").append(d.snippet.replace("\n", " ")).append('\n');
            }
            sb.append('\n');
        }

        // Suggested queries.
        sb.append("## Suggested Queries\n\n");
        if (r.suggestedQueries.isEmpty()) sb.append("_None._\n");
        else for (String s : r.suggestedQueries) sb.append("- `").append(s).append("`\n");

        return sb.toString();
    }

    private static String formatPackage(EnrichResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(r.pkg).append(" (package)\n\n");

        sb.append("## Types\n\n");
        if (r.members.isEmpty()) {
            sb.append("_Empty._\n\n");
        } else {
            sb.append("| Label | Kind | Qualified Name |\n|---|---|---|\n");
            for (NodeRow m : r.members) {
                sb.append("| ").append(m.label)
                  .append(" | ").append(m.kind)
                  .append(" | `").append(m.qualifiedName).append("` |\n");
            }
            sb.append('\n');
        }

        if (!r.semanticAnnotations.isEmpty()) {
            sb.append("## Semantic Annotations\n\n");
            sb.append("| Category | Label | Source |\n|---|---|---|\n");
            for (SemanticAnnotationRow s : r.semanticAnnotations) {
                sb.append("| ").append(nullSafe(s.category))
                  .append(" | ").append(nullSafe(s.businessLabel))
                  .append(" | ").append(nullSafe(s.source))
                  .append(" |\n");
            }
            sb.append('\n');
        }

        if (!r.packageDeps.isEmpty()) {
            sb.append("## Package Dependencies\n\n");
            sb.append("| Target | Relation | Count |\n|---|---|---|\n");
            for (Map<String, Object> d : r.packageDeps) {
                sb.append("| ").append(d.get("target_package"))
                  .append(" | ").append(d.get("relation"))
                  .append(" | ").append(d.get("edge_count"))
                  .append(" |\n");
            }
            sb.append('\n');
        }

        if (!r.relatedDocs.isEmpty()) {
            sb.append("## Related Documentation\n\n");
            for (DocSnippet d : r.relatedDocs) {
                sb.append("- **").append(nullSafe(d.title)).append("** (`")
                  .append(nullSafe(d.path)).append("`)\n");
                if (d.snippet != null) sb.append("  > ").append(d.snippet.replace("\n", " ")).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## Suggested Queries\n\n");
        if (r.suggestedQueries.isEmpty()) sb.append("_None._\n");
        else for (String s : r.suggestedQueries) sb.append("- `").append(s).append("`\n");

        return sb.toString();
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
