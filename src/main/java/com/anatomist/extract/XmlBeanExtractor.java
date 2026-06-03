package com.anatomist.extract;

import com.anatomist.core.SpringBeanParser.ParsedBean;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps parsed Spring beans into graph nodes/edges. Unlike the JavaParser-driven
 * {@link Extractor}s this runs as a separate pass (no {@code CompilationUnit}):
 * it joins each bean's {@code class} FQN and {@code ref} bean names against the
 * set of node ids already known (this batch's Java CLASS nodes ∪ the DB), so
 * internal vs. external edges obey the {@code edges} CHECK constraint.
 *
 * <p>Emitted graph:</p>
 * <ul>
 *   <li>one {@code BEAN} node per concrete bean (id {@code bean:<name>@<relXml>});</li>
 *   <li>{@code DEFINED_BY}: BEAN → its class (internal if the class id is known,
 *       else external);</li>
 *   <li>{@code WIRES}: ownerClass → refClass (CLASS→CLASS, so it folds into the
 *       existing deps-of / used-by CTEs). Only emitted when the owner bean's class
 *       is a known internal node and the ref resolves to another declared bean's
 *       class.</li>
 * </ul>
 */
public final class XmlBeanExtractor {

    private final String scope;

    public XmlBeanExtractor() { this("MAIN"); }

    public XmlBeanExtractor(String scope) {
        this.scope = scope != null ? scope : "MAIN";
    }

    public void extract(List<ParsedBean> beans, Set<String> knownIds,
                        String sourceFile, ExtractionResult result) {
        // bean name → its declared class FQN, for resolving refs to classes.
        Map<String, String> beanClass = new HashMap<>();
        for (ParsedBean b : beans) beanClass.putIfAbsent(b.name(), b.className());

        for (ParsedBean b : beans) {
            String beanId = beanNodeId(b.name(), sourceFile);
            String cls = b.className();
            boolean classKnown = knownIds.contains(cls);

            Node n = new Node();
            n.id = beanId;
            n.label = b.name();
            n.kind = "BEAN";
            n.qualifiedName = b.name();
            n.sourceFile = sourceFile;
            n.sourceLocation = "L" + b.line();
            n.scope = scope;
            n.metadata = metadataJson(cls, b.refs());
            result.nodes.add(n);

            // DEFINED_BY: BEAN → class.
            Edge def = baseEdge("DEFINED_BY", sourceFile, b.line());
            def.sourceId = beanId;
            if (classKnown) { def.targetId = cls; def.isExternal = false; }
            else { def.externalTargetFqn = cls; def.isExternal = true; }
            result.edges.add(def);

            // WIRES: ownerClass → refClass. Needs a known internal owner class node.
            if (!classKnown) continue;
            for (String ref : b.refs()) {
                String refClass = beanClass.get(ref);
                if (refClass == null) continue; // ref to undeclared bean → unresolvable
                Edge w = baseEdge("WIRES", sourceFile, b.line());
                w.sourceId = cls;
                if (knownIds.contains(refClass)) { w.targetId = refClass; w.isExternal = false; }
                else { w.externalTargetFqn = refClass; w.isExternal = true; }
                result.edges.add(w);
            }
        }
    }

    public static String beanNodeId(String beanName, String sourceFile) {
        return "bean:" + beanName + "@" + sourceFile;
    }

    private static Edge baseEdge(String relation, String sourceFile, int line) {
        Edge e = new Edge();
        e.relation = relation;
        e.confidence = "EXTRACTED";
        e.sourceFile = sourceFile;
        e.sourceLocation = "L" + line;
        return e;
    }

    private static String metadataJson(String className, List<String> refs) {
        StringBuilder sb = new StringBuilder("{\"className\":");
        appendJsonString(sb, className);
        sb.append(",\"refs\":[");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) sb.append(',');
            appendJsonString(sb, refs.get(i));
        }
        return sb.append("]}").toString();
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        if (s == null) { sb.append("null"); return; }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }
}
